package com.xpay.demo.rocketmq;

import com.xpay.common.statics.dto.mq.MsgDto;
import com.xpay.common.utils.AmountUtil;
import com.xpay.common.utils.DateUtil;
import com.xpay.common.utils.JsonUtil;
import com.xpay.common.utils.RandomUtil;
import com.xpay.demo.rocketmq.bizVo.ItemVo;
import com.xpay.demo.rocketmq.bizVo.OrderVo;
import com.xpay.starter.plugin.consts.MQHeaders;
import com.xpay.starter.plugin.plugins.MQSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/demo")
public class DemoController {
    @Autowired
    MQSender rmqSender;

    @RequestMapping("sendOne")
    public Mono<String> sendOne(){
        int rand = RandomUtil.getInt(2);

        OrderVo vo = new OrderVo();
        vo.setTopic(Destinations.TOPIC_SIMPLE);
        vo.setTags(rand%2==0 ? Destinations.TAGS_ONE : Destinations.TAGS_TWO);
        vo.setTrxNo(genTrxNo());
        vo.setAmount(genAmount());
        vo.setIsFinish(true);

        long start = System.currentTimeMillis();
        rmqSender.sendOne(vo);
        long timeCost = ((System.currentTimeMillis()-start));
        System.out.println("sendOne timeCost="+timeCost);
        return Mono.just("ok");
    }

    @RequestMapping(value = "/sendBatch")
    public Mono<String> sendBatch() {
        List<OrderVo> voList = new ArrayList<>();
        int batchCount =  RandomUtil.getInt(8, 20);

        for(int i=1; i<=batchCount; i++){
            int rand = RandomUtil.getInt(2);

            OrderVo vo = new OrderVo();
            vo.setTopic(Destinations.TOPIC_BATCH);

            vo.setTags(rand%2==0 ? Destinations.TAGS_ONE : Destinations.TAGS_TWO);
            vo.setTrxNo(genTrxNo());

            BigDecimal amount = BigDecimal.ZERO;
            List<ItemVo> itemVos = new ArrayList<>();
            for(int j=1; j<=2; j++){
                ItemVo itemVo = new ItemVo();
                itemVo.setItemNo(vo.getTrxNo() + "_" + j);
                itemVo.setItemAmount(genAmount());
                itemVos.add(itemVo);

                amount = AmountUtil.add(amount, itemVo.getItemAmount());
            }
            vo.setItemVoList(itemVos);

            vo.setAmount(genAmount());
            vo.setIsFinish(true);

            voList.add(vo);
        }

        boolean isSameTag = RandomUtil.getInt(2) % 2 == 0;
        long start = 0;
        if(isSameTag){
            String tag = RandomUtil.getInt(2) % 2 == 0 ? Destinations.TAGS_ONE : Destinations.TAGS_TWO;
            String dest = Destinations.TOPIC_BATCH + ":" + tag;
            start = System.currentTimeMillis();
            rmqSender.sendBatch(dest, voList);
        }else{
            start = System.currentTimeMillis();
            rmqSender.sendBatch(voList);
        }
        long timeCost = ((System.currentTimeMillis()-start));
        System.out.println("sendBatch batchCount="+batchCount+",timeCost="+timeCost);
        return Mono.just("true");
    }

    @RequestMapping(value = "/sendTrans")
    public Mono<String> sendTrans() {
        int rand = RandomUtil.getInt(1);

        OrderVo vo = new OrderVo();
        vo.setTopic(Destinations.TOPIC_TRANS);
        vo.setTags(rand==0 ? Destinations.TAGS_ONE : Destinations.TAGS_TWO);
        vo.setTrxNo(genTrxNo());

        vo.setAmount(genAmount());
        vo.setIsFinish(false);

        long start = System.currentTimeMillis();
        rmqSender.sendTrans(vo);
        long timeCost = ((System.currentTimeMillis()-start));
        System.out.println("sendTrans timeCost="+timeCost);
        return Mono.just("true");
    }

    @RequestMapping("sendDelay")
    public Mono<String> sendDelay(){
        OrderVo msg = new OrderVo();
        msg.setTopic(Destinations.TOPIC_DELAY);
        msg.setTags(Destinations.TAGS_ONE);//?????????????????????????????? ???????????????????????????????????????
        msg.setTrxNo(genTrxNo());

        int delaySec = RandomUtil.getInt(1, 6) * 5;
        msg.addHeader(MQHeaders.SCHEDULED_DELAY, String.valueOf(delaySec));
        long start = System.currentTimeMillis();
        rmqSender.sendOne(msg);
        long timeCost = ((System.currentTimeMillis()-start));
        System.out.println(DateUtil.formatDateTimeMills(new Date()) + " sendDelay timeCost="+timeCost + ",delaySec="+delaySec);
        return Mono.just("ok");
    }

    /**
     * ??????????????????(????????????)
     * @return
     */
    @RequestMapping("sendOrderly")
    public Mono<String> sendOrderly() {
        long timeout = 2000;
        for(int i=1; i<=5; i++) {
            OrderVo msg = new OrderVo();
            msg.setTopic(Destinations.TOPIC_ORDERLY);
            msg.setTags(Destinations.TAGS_ONE);
            msg.setTrxNo(genTrxNo());
            msg.addParam("msg", "????????????");
            rmqSender.sendOrderly(msg, msg.getTrxNo(), timeout);
            msg.addParam("msg", "???????????????");
            rmqSender.sendOrderly(msg, msg.getTrxNo(), timeout);
            msg.addParam("msg", "???????????????");
            rmqSender.sendOrderly(msg, msg.getTrxNo(), timeout);
        }
        return Mono.just("ok");
    }

    /**
     * ??????failOver?????????????????????????????????Broker???????????????????????????&?????????????????????????????????Broker??????????????????????????????????????????Broker????????????&??????
     * @return
     */
    @RequestMapping("failOver")
    public Mono<String> failOver(){
        String trxNo = "100000000001_";
        MsgDto msg = new MsgDto();
        msg.setTopic(Destinations.TOPIC_FAILOVER);
        msg.setTags("topicGroup_2");//?????????????????????????????? ???????????????????????????????????????
        msg.addParam("key_2", "value_2");
        msg.addParam("key_3", "value_3");
        for(int i=0; i<100000; i++){
            try{
                msg.setTrxNo(trxNo + i);
                rmqSender.sendOne(msg);

                Thread.sleep(500);
            }catch(Exception e){
                e.printStackTrace();
            }
        }
        return Mono.just("ok");
    }

    @RequestMapping("sendAndReply")
    public Mono<String> sendAndReply(){
        int rand = RandomUtil.getInt(10);

        OrderVo vo = new OrderVo();
        vo.setTopic(Destinations.TOPIC_REPLY);
        vo.setTags("*");
        vo.setTrxNo(genTrxNo());
        vo.setAmount(genAmount());
        vo.setIsFinish(true);

        long start = System.currentTimeMillis();
        if(rand % 2 == 0){
            MsgDto msg = rmqSender.sendAndReceive(vo);
            System.out.println("?????????????????????????????????????????????" + JsonUtil.toJson(msg));
        }else{
            rmqSender.sendAndReceive(vo, (msgDto) -> {
                if(msgDto.getCause() == null){
                    System.out.println("?????????????????????????????????????????????" + JsonUtil.toJson(msgDto));
                }else{
                    System.out.println("???????????????????????????????????????" + msgDto.getCause().getMessage());
                }
            });
        }

        long timeCost = ((System.currentTimeMillis()-start));
        System.out.println("sendAndReply timeCost="+timeCost);
        return Mono.just("ok");
    }

    private String genTrxNo(){
        return "BL" + DateUtil.formatShortDate(new Date()) + RandomUtil.getDigitStr(5);
    }
    private BigDecimal genAmount(){
        return BigDecimal.valueOf(Double.valueOf(RandomUtil.getDigitStr(3) + "." + RandomUtil.getDigitStr(2)));
    }
}
