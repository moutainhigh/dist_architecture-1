package com.xpay.service.message.task;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.xpay.common.utils.IPUtil;
import com.xpay.common.utils.JsonUtil;
import com.xpay.common.utils.HttpUtil;
import com.xpay.common.utils.StringUtil;
import com.xpay.service.message.config.properties.AMQMonitorProperties;
import com.xpay.service.message.listener.event.MessageBacklogEvent;
import com.xpay.service.message.listener.event.MessageNoConsumerEvent;
import com.xpay.service.message.listener.event.MonitorExceptionEvent;
import com.xpay.service.message.listener.event.SyncMetaDataEvent;
import com.xpay.service.message.listener.model.ExceptionMsg;
import com.xpay.service.message.listener.model.MetaDataDto;
import com.xpay.service.message.listener.model.QueueProperty;
import com.xpay.service.message.utils.ActiveMQUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ActiveMQMonitorTask {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);
    private ApplicationEventPublisher publisher;

    private Map<String, AMQMonitorProperties.Node> nodeMap;
    private List<String> omitQueues = new ArrayList<>();
    private Map<String, Integer> blockSizeMap;
    private int defaultBlockSize;
    private int interval;
    private static int dest_type_queue = 1;
    private static int dest_type_vtopic = 2;
    private String hostName = IPUtil.getLocalHost();

    public ActiveMQMonitorTask(AMQMonitorProperties monitorProperties, ApplicationEventPublisher publisher){
        this.publisher = publisher;

        if(StringUtil.isNotEmpty(monitorProperties.getOmitQueues())){
            this.omitQueues = Arrays.asList(monitorProperties.getOmitQueues().split(","));
        }
        this.blockSizeMap = monitorProperties.getBlockSize();
        this.defaultBlockSize = monitorProperties.getDefaultBlockSize();
        this.interval = monitorProperties.getInterval();

        Map<String, AMQMonitorProperties.Node> nodeMap = new HashMap<>();
        for(AMQMonitorProperties.Node node : monitorProperties.getNodes()){
            String key = node.getHost() + ":" + node.getPort();
            nodeMap.put(key, node);
        }
        this.nodeMap = nodeMap;
        init();
    }

    private void init(){
        int nodeSize = nodeMap.size();
        if(nodeSize <= 0){
            return;
        }

        scheduledExecutorService.scheduleAtFixedRate(() -> doBrokerQueueMonitor(), 5, interval, TimeUnit.SECONDS);

        if(nodeSize > 1){
            scheduledExecutorService.scheduleAtFixedRate(() -> doMetaDataMonitor(), 5, interval, TimeUnit.SECONDS);
        }
        logger.info("hostName={} ???????????????????????????", hostName);
    }

    private void doBrokerQueueMonitor(){
        for(Map.Entry<String, AMQMonitorProperties.Node> entry : nodeMap.entrySet()){
            AMQMonitorProperties.Node node = entry.getValue();

            long start = System.currentTimeMillis();
            try{
                int queueNum = queueMonitor(node);
                logger.info("??????Queue?????????????????? host={} port={} brokerName={} queueNum={} timeCost={}(??????) ", node.getHost(), node.getPort(), node.getBrokerName(), queueNum, (System.currentTimeMillis()-start));
            }catch(Exception e){
                logger.error("???????????????????????? host={} port={} brokerName={} ", node.getHost(), node.getPort(), node.getBrokerName(), e);
                publisher.publishEvent(new MonitorExceptionEvent(new ExceptionMsg(appendHost(node.getBrokerName()), e, node.getHost()+":"+node.getPort()+" ????????????????????????")));
            }
        }
    }

    /**
     * ?????????????????????Queue???VirtualTopic????????????Broker??????Queue???VirtualTopic????????????
     */
    private void doMetaDataMonitor(){
        long start = System.currentTimeMillis();

        //????????????Broker?????????????????????
        Map<String, Set<String>> brokerDestinationMap = getAllDestination();
        //????????????????????????????????????
        Set<String> allDestinationSet = new LinkedHashSet<>();
        for(Map.Entry<String, Set<String>> entry : brokerDestinationMap.entrySet()){
            for(String destName : entry.getValue()){
                if(! allDestinationSet.contains(destName)){
                    allDestinationSet.add(destName);
                }
            }
        }
        //???????????????Broker????????????????????????
        Map<String, Set<String>> needSyncDestinationMap = new HashMap<>();
        for(String destName : allDestinationSet){
            for(Map.Entry<String, Set<String>> entry : brokerDestinationMap.entrySet()){
                String key = entry.getKey();
                Set<String> destNameSet = entry.getValue();
                if(destNameSet.contains(destName)){//??????Broker??????????????????????????????
                    continue;
                }

                if(! needSyncDestinationMap.containsKey(key)){
                    Set<String> destNameSetTemp = new LinkedHashSet<>();
                    needSyncDestinationMap.put(key, destNameSetTemp);
                }
                needSyncDestinationMap.get(key).add(destName);
            }
        }
        //??????Broker????????????????????????
        int totalSync = 0;
        for(Map.Entry<String, Set<String>> entry : needSyncDestinationMap.entrySet()){
            String key = entry.getKey();
            Set<String> destNameSet = entry.getValue();
            AMQMonitorProperties.Node node = nodeMap.get(key);

            MetaDataDto metaDataDto = new MetaDataDto();
            metaDataDto.setNode(node);
            metaDataDto.setDestinations(destNameSet);

            totalSync += destNameSet.size();

            publisher.publishEvent(new SyncMetaDataEvent(metaDataDto));

            logger.info("??????????????????????????? host={} port={} needSyncDestination.size={} needSyncDestination={}", node.getHost(), node.getPort(), destNameSet.size(), JsonUtil.toJson(destNameSet));
        }

        logger.info("????????????????????????????????? nodes.size={} allDestinations.size={} totalSyncDestination={} timeCost={}(??????)", nodeMap.size(), allDestinationSet.size(), totalSync, (System.currentTimeMillis() - start));
    }

    private int queueMonitor(AMQMonitorProperties.Node brokerConf) throws Exception {
        List<QueueProperty> backlogList = new ArrayList<>();//????????????????????????
        List<QueueProperty> noConsumeList = new ArrayList<>();//?????????????????????

        Set<String> queueSet = getDestinationByType(brokerConf, dest_type_queue);
        if(queueSet == null || queueSet.isEmpty()){
            return 0;
        }

        String url = brokerConf.getHost() + ":" + brokerConf.getPort() + "/api/jolokia/read/org.apache.activemq:type=Broker,brokerName=" + brokerConf.getBrokerName() + ",destinationType=Queue,destinationName=";
        Map<String, String> header = ActiveMQUtil.buildHeader(brokerConf);

        for(String queueName : queueSet) {
            String realUrl = url + queueName;
            HttpUtil.Response response = HttpUtil.getSync(realUrl, header, null);
            if (response.getBodyString() == null || response.getBody().length == 0) {
                logger.error("?????????????????? queueName={} url={}", queueName, realUrl);
                continue;
            }

            HashMap<String, Object> data = JsonUtil.toBean(response.getBody(), HashMap.class);
            Object value = data.get("value");
            if (value == null) {
                logger.error("??????????????????value?????? queueName={} queueInfo={}", queueName, response.getBodyString());
                continue;
            }

            JSONObject obj = (JSONObject) value;
            // ??????????????????
            long queueSize = obj.getLongValue("QueueSize");
            // ???????????????
            long producerCount = obj.getLongValue("ProducerCount");
            // ????????????
            long consumerCount = obj.getLongValue("ConsumerCount");
            // ??????????????????
            long enqueueCount = obj.getLongValue("EnqueueCount");
            // ??????????????????
            long dequeueCount = obj.getLongValue("DequeueCount");
            // ????????????????????????
            boolean paused = obj.getBooleanValue("Paused");

            QueueProperty queueProperty = QueueProperty.build()
                    .setBrokerName(appendHost(brokerConf.getBrokerName()))
                    .setQueueName(queueName)
                    .setQueueSize(queueSize)
                    .setProducerCount(producerCount)
                    .setConsumerCount(consumerCount)
                    .setEnqueueCount(enqueueCount)
                    .setDequeueCount(dequeueCount)
                    .setPaused(paused);

            int blockSize = blockSizeMap.get(queueName) != null ? blockSizeMap.get(queueName) : defaultBlockSize;
            if(queueProperty.getQueueSize() > 0 && queueProperty.getConsumerCount() <= 0){
                //??????????????????
                noConsumeList.add(queueProperty);
            }else if(queueProperty.getQueueSize() > blockSize){
                //?????????
                backlogList.add(queueProperty);
            }
        }

        // ??????????????????
        if (!backlogList.isEmpty()) {
            publisher.publishEvent(new MessageBacklogEvent(backlogList));
        }

        // ??????????????????
        if (!noConsumeList.isEmpty()) {
            publisher.publishEvent(new MessageNoConsumerEvent(noConsumeList));
        }
        return queueSet.size();
    }

    /**
     * ????????????Broker???????????????
     * @return map???key???broker???ip:port???value?????????broker?????????????????????
     */
    private Map<String, Set<String>> getAllDestination() {
        Map<String, Set<String>> destMameMap = new HashMap<>();

        for(Map.Entry<String, AMQMonitorProperties.Node> entry : nodeMap.entrySet()){
            AMQMonitorProperties.Node node = entry.getValue();

            try{
                Set<String> destNameSet = new LinkedHashSet<>();
                Set<String> topicSet = getDestinationByType(node, dest_type_vtopic);
                Set<String> queueSet = getDestinationByType(node, dest_type_queue);

                if(topicSet != null && ! topicSet.isEmpty()){
                    destNameSet.addAll(topicSet);
                }
                if(queueSet != null && ! queueSet.isEmpty()){
                    destNameSet.addAll(queueSet);
                }

                destMameMap.put(entry.getKey(), destNameSet);
            }catch(Exception e){
                logger.error("host={} port={} brokerName={} ?????????????????????????????????", node.getHost(), node.getPort(), node.getBrokerName(), e);
                publisher.publishEvent(new MonitorExceptionEvent(new ExceptionMsg(appendHost(node.getBrokerName()), e, node.getHost()+":"+node.getPort()+" ?????????????????????????????????")));
            }
        }
        return destMameMap;
    }

    private Set<String> getDestinationByType(AMQMonitorProperties.Node brokerConf, int destType) throws Exception {
        Set<String> destNameSet = new LinkedHashSet();
        String url = brokerConf.getHost() + ":" + brokerConf.getPort() + "/api/jolokia/read/org.apache.activemq:brokerName=" + brokerConf.getBrokerName();
        boolean isQueue = destType == dest_type_queue;
        String realUrl = url + ",type=Broker/" + (isQueue ? "Queues" : "Topics");

        Map<String, String> header = ActiveMQUtil.buildHeader(brokerConf);
        HttpUtil.Response response = HttpUtil.getSync(realUrl, header, null);
        if (response.getBody() == null || response.getBody().length == 0) {
            logger.error("???Broker?????????????????????????????????url = {}", realUrl);
            return destNameSet;
        }

        HashMap<String, Object> valueMap = JsonUtil.toBean(response.getBody(), HashMap.class);
        JSONArray valueJArr = valueMap.get("value") == null ? null : (JSONArray) valueMap.get("value");
        if (valueJArr == null) {
            logger.error("???Broker????????????value???????????????url = {}", realUrl);
            return destNameSet;
        }

        Iterator<Object> iterator = valueJArr.iterator();
        while (iterator.hasNext()) {
            JSONObject obj = (JSONObject) iterator.next();
            Map<String, String> map = objectNameToMap(obj.getString("objectName"));
            String destName = map.get("destinationName");
            //????????????????????????????????????2????????????????????????????????????2???ActiveMQ??????????????????queue???topic???3??????VirtualTopic???Topic
            if (omitQueues.contains(destName) || ActiveMQUtil.isActiveMQInnerDestination(destName)) {
                continue;
            } else if (!isQueue && !ActiveMQUtil.isVirtualTopic(destName)) { //?????????Topic???????????????????????????????????????
                continue;
            }
            destNameSet.add(destName);
        }
        return destNameSet;
    }

    private Map<String, String> objectNameToMap(String objectName){
        Map<String, String> map = new HashMap<>();
        if(StringUtil.isEmpty(objectName)){
            return map;
        }
        String[] elements = objectName.split(",");
        for(String name : elements){
            String[] kvArr = name.split("=");
            map.put(kvArr[0], kvArr[1]);
        }
        return map;
    }

    private String appendHost(String brokerName){
        return brokerName + " : " + hostName;
    }
}
