package com.xpay.starter.plugin.client;

import com.google.common.cache.Cache;
import com.xpay.common.statics.dto.es.EsAgg;
import com.xpay.common.statics.query.EsQuery;
import com.xpay.common.statics.result.EsAggResult;
import com.xpay.common.statics.result.PageResult;
import com.xpay.starter.plugin.util.Utils;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.*;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.elasticsearch.client.indices.*;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.ReindexRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.*;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * elasticsearch???????????????????????????????????????????????????????????????????????????????????? {@link #getRestEsClient()}????????????ES????????????????????????????????????ES???
 * ????????????????????????????????????????????????????????? ES ??? MYSQL ????????????????????????

 team ????????? =

 teams ????????? in

 range ????????? between???>???>=???<???<=
   gt ????????? >
   gte ????????? >=
   lt ????????? <
   lte ????????? <=
   gte???lte ????????? between ?????????????????????sql?????? a >= 1 and a <= 5

 match ????????? like?????????ES?????????????????????????????? MYSQL ?????????????????????????????????

 //?????????sql?????????ES??????????????????
 //1.????????????????????????must???filter?????????
 select * from table where a=1 and b=2 and c=3 and d not in(5,6)
 POST _search
 {
   "query": {
     "bool" : {
       "filter" : {
         "term" : { "a" : "1" },
         "term" : { "b" : "2" },
         "term" : { "c" : "3" }
       },
       "must_not": {
         "terms" : { "d" : [5,6] }
       }
     }
   }
 }

 //2.????????????????????????should?????????
 select * from table where a=1 or b=2 or c=3
 POST _search
 {
   "query": {
     "bool" : {
       "should" : {
         "term" : { "a" : "1" },
         "term" : { "b" : "2" }
         "term" : { "c" : "3" }
       }
     }
   }
 }

 //3.?????????????????????should?????????
 select * from table where a=1 or (b=2 and c=3)
 POST _search
 {
   "query": {
     "bool" : {
       "should" : {
         "term" : { "a" : "1" },
         "bool" : {
           "must" : {
             "term" : { "b" : "2" },
             "term" : { "c" : "3" }
           }
         }
       }
     }
   }
 }

 //4.???????????????????????????
 select * from where a=1 and b=2 and (c=3 or d=4) and e not in(5,6)
 POST _search
 {
   "query": {
     "bool" : {
       "filter" : {
         "term" : { "a" : "1" },
         "term" : { "b" : "2" },
         "bool" : {
           "should" : {
             "term" : { "c" : "3" },
             "term" : { "d" : "4" }
           }
         }
       },
       "must_not": {
         "terms" : { "e" : [5,6] }
       }
     }
   }
 }

 * @author chenyf
 */
public class ESClient {
    public static final int MAX_GROUP_SIZE = 1000;//??????????????????
    private static final String DEFAULT_GROUP_VALUE = "_DEFAULT_GROUP_VALUE_";
    private final InnerParamHelper paramHelper = new InnerParamHelper();
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final RestHighLevelClient restEsClient;
    private final Cache<String, Map<String, String>> cache;

    public ESClient(RestHighLevelClient restEsClient){
        this(restEsClient, null);
    }

    public ESClient(RestHighLevelClient restEsClient, Cache<String, Map<String, String>> cache){
        this.restEsClient = restEsClient;
        this.cache = cache;
    }

    /**
     * ??????es???????????????????????????????????????????????????
     * @return
     */
    public RestHighLevelClient getRestEsClient() {
        return restEsClient;
    }

    /**
     * ??????????????????
     * @param esQuery   es????????????
     * @param <T>       ?????????????????????
     * @return
     */
    public <T> T getOne(EsQuery esQuery){
        Class<T> clz = getReturnClass(esQuery);

        SearchResponse response = executeQuery(esQuery);
        if(response.getHits().getTotalHits().value > 0){
            SearchHit searchHit = response.getHits().getHits()[0];
            if(esQuery.isWordCase()){
                Map<String, Object> resultMap = resultWordCase(searchHit.getSourceAsMap(), esQuery.isCamelCase());
                if(isHashMap(clz)){
                    return (T) resultMap;
                }else if(isString(clz)){
                    return (T) Utils.toJson(resultMap);
                }else{
                    return Utils.jsonToBean(Utils.toJson(resultMap), clz);
                }
            }else{
                if(isHashMap(clz)){
                    return (T) searchHit.getSourceAsMap();
                }else if(isString(clz)){
                    return (T) searchHit.getSourceAsString();
                }else{
                    return Utils.jsonToBean(searchHit.getSourceAsString(), clz);
                }
            }
        }else{
            return null;
        }
    }

    /**
     * ??????id??????????????????
     * @param index     ?????????
     * @param id        ??????id
     * @return
     */
    public Map<String, Object> getById(String index, Long id){
        if(isEmpty(index)) {
            throw new RuntimeException("index????????????");
        }else if(id == null){
            throw new RuntimeException("id????????????");
        }

        GetRequest getRequest = new GetRequest(index, id.toString());
        try {
            GetResponse response = restEsClient.get(getRequest, RequestOptions.DEFAULT);
            return response.getSourceAsMap();
        } catch (Exception e) {
            throw new RuntimeException("getById??????", e);
        }
    }

    /**
     * ????????????id??????????????????
     * @param index     ?????????
     * @param ids       id??????
     * @return
     */
    public List<Map<String, Object>> listById(String index, List<Long> ids){
        if(isEmpty(index)){
            throw new RuntimeException("index????????????");
        }else if(ids == null || ids.isEmpty()){
            throw new RuntimeException("ids????????????");
        }

        String[] idsArr = new String[ids.size()];
        for(int i=0; i<ids.size(); i++){
            idsArr[i] = ids.get(i).toString();
        }

        SearchSourceBuilder builder = new SearchSourceBuilder();
        builder.from(0)
                .size(ids.size())
                .query(QueryBuilders.idsQuery().addIds(idsArr));

        SearchRequest request = new SearchRequest(index);
        request.source(builder);

        SearchResponse response;
        try {
            response = restEsClient.search(request, RequestOptions.DEFAULT);
        } catch (Exception e) {
            throw new RuntimeException("listById??????", e);
        }

        List<Map<String, Object>> entityList = new ArrayList<>(ids.size());
        SearchHit[] hits = response.getHits().getHits();
        for(int i=0; i<hits.length; i++){
            entityList.add(hits[i].getSourceAsMap());
        }
        return entityList;
    }

    /**
     * ????????????
     * @param esQuery   es????????????
     * @param <T>       ?????????????????????
     * @return
     */
    public <T> List<T> listBy(EsQuery esQuery){
        Class<T> clz = getReturnClass(esQuery);

        SearchResponse response = executeQuery(esQuery);
        if(response.getHits().getTotalHits().value <= 0){
            return new ArrayList<>();
        }

        List<T> entityList = getEntityList(response, clz, esQuery);
        if((entityList == null || entityList.isEmpty()) && Utils.isNotEmpty(response.getScrollId())){
            clearScrollAsync(response.getScrollId());
        }
        return entityList;
    }

    /**
     * ????????????????????????
     * @param esQuery   es????????????
     * @return
     */
    public long countBy(EsQuery esQuery){
        //????????????
        paramCheck(esQuery, false);
        //????????????????????????
        esQuery.doQueryParamCase(paramHelper);

        String index = esQuery.getIndex();
        CountRequest request = new CountRequest(index);
        request.query(getQueryBuilder(esQuery));//??????????????????
        CountResponse response;
        try {
            response = restEsClient.count(request, RequestOptions.DEFAULT);
            if (RestStatus.OK.equals(response.status())
                    && (response.isTerminatedEarly() == null || Boolean.FALSE.equals(response.isTerminatedEarly()))
                    && response.getFailedShards() <= 0) {
                return response.getCount();
            }
        } catch (Exception e) {
            throw new RuntimeException("countBy??????", e);
        }

        StringBuilder failMsg = new StringBuilder();
        for (ShardSearchFailure failure : response.getShardFailures()) {
            if(failMsg.length() > 0) {
                failMsg.append(",");
            }
            failMsg.append("{nodeId:").append(failure.shard().getNodeId()).append(",")
                    .append("shardId:").append(failure.shard().getShardId()).append(",")
                    .append("reason:").append(failure.reason()).append("}");
        }
        logger.warn("countBy???????????????????????????????????? index: {} failMsg: {}", index, failMsg.toString());
        throw new RuntimeException("??????????????????????????????????????????????????????????????????");
    }

    /**
     * ???????????????????????????????????????
     * @param esQuery   es????????????
     * @param <T>       ?????????????????????
     * @return
     */
    public <T> PageResult<List<T>> listPage(EsQuery esQuery){
        Class<T> clz = getReturnClass(esQuery);
        SearchResponse response = executeQuery(esQuery);
        long totalRecord = response.getHits().getTotalHits().value;
        if(totalRecord <= 0){
            return PageResult.newInstance(new ArrayList<>(), esQuery.getPageCurrent(), esQuery.getPageSize());
        }

        String scrollId = response.getScrollId();
        List<T> entityList = getEntityList(response, clz, esQuery);
        if(Utils.isNotEmpty(scrollId) && (entityList == null || entityList.isEmpty() || entityList.size() < esQuery.getPageSize())){
            clearScrollAsync(scrollId);
            scrollId = null;//ES??????????????????????????????scrollId?????????????????????
        }
        PageResult<List<T>> result = PageResult.newInstance(entityList, esQuery.getPageCurrent(), esQuery.getPageSize(), totalRecord);
        result.setScrollId(scrollId);
        return result;
    }

    /**
     * ????????????????????????????????????????????????????????????????????????count???sum???min???max???avg ???????????????
     * @param esQuery   es????????????
     * @return
     */
    public EsAggResult aggregation(EsQuery esQuery){
        SearchResponse response = executeAggregation(esQuery);

        AggResult aggResult = new AggResult();
        aggResult.setGroupField(esQuery.getGroupBy());
        if(response.getHits().getTotalHits().value > 0){
            if (isEmpty(esQuery.getGroupBy())){
                fillEsAggResult(aggResult, DEFAULT_GROUP_VALUE, response.getAggregations().iterator(), esQuery);
            }else{
                Iterator<Aggregation> iterator = response.getAggregations().iterator();
                while (iterator.hasNext()){
                    Aggregation agg = iterator.next();
                    ParsedTerms terms = (ParsedTerms) agg;

                    if(terms.getBuckets().isEmpty()){
                        continue;
                    }

                    for(Terms.Bucket bucket : terms.getBuckets()){
                        String groupValue = bucket.getKeyAsString();
                        Aggregations bucketAgg = bucket.getAggregations();
                        fillEsAggResult(aggResult, groupValue, bucketAgg.iterator(), esQuery);
                    }
                }
            }
        }
        return aggResult.toEsAggResult();
    }

    /**
     * ???????????????????????????List<T>??????
     * @param response      ???????????????
     * @param clz           ???????????????Class??????
     * @param esQuery       es????????????
     * @param <T>           ?????????????????????
     * @return
     */
    public <T> List<T> getEntityList(SearchResponse response, Class<T> clz, EsQuery esQuery){
        List<T> entityList = new ArrayList<>();
        boolean isHashMap = isHashMap(clz);
        boolean isString = isString(clz);
        if(response.getHits().getHits().length <= 0){
            return entityList;
        }

        SearchHit[] hits = response.getHits().getHits();
        for(int i=0; i<hits.length; i++){
            SearchHit searchHit = hits[i];
            if(esQuery.isWordCase()){
                Map<String, Object> resultMap = resultWordCase(searchHit.getSourceAsMap(), esQuery.isCamelCase());
                if(isHashMap){
                    entityList.add((T) resultMap);
                }else if(isString){
                    entityList.add((T) Utils.toJson(resultMap));
                }else{
                    entityList.add(Utils.jsonToBean(Utils.toJson(resultMap), clz));
                }
            }else{
                if(isHashMap){
                    entityList.add((T) searchHit.getSourceAsMap());
                }else if(isString){
                    entityList.add((T) searchHit.getSourceAsString());
                }else{
                    entityList.add(Utils.jsonToBean(searchHit.getSourceAsString(), clz));
                }
            }
        }
        return entityList;
    }

    /**
     * ??????????????????????????????
     * @param index     ?????????
     * @param id        ??????id
     * @return
     */
    public boolean exists(String index, Long id) {
        if (isEmpty(index) || id == null) {
            throw new RuntimeException("index???id????????????");
        }

        GetRequest getRequest = new GetRequest(index, id.toString());
        getRequest.fetchSourceContext(new FetchSourceContext(false));
        getRequest.storedFields("_none_");
        try {
            boolean exists = restEsClient.exists(getRequest, RequestOptions.DEFAULT);
            return exists;
        } catch (Exception e) {
            throw new RuntimeException("????????????????????????????????? index="+index+",id="+id, e);
        }
    }

    /**
     * ??????????????????
     * @param index     ?????????
     * @param id        ?????????id
     * @param version   ?????????
     * @return
     */
    public boolean delete(String index, Long id, Long version){
        if (isEmpty(index) || id == null) {
            throw new RuntimeException("index???id????????????");
        }

        DeleteRequest request = new DeleteRequest(index, id.toString());
        if (version != null) {
            request.version(version);
        }

        DeleteResponse response;
        try {
            response = restEsClient.delete(request, RequestOptions.DEFAULT);
            if (DocWriteResponse.Result.DELETED.equals(response.getResult())
                    || DocWriteResponse.Result.NOT_FOUND.equals(response.getResult())) {
                return true;//?????????????????????????????????????????????????????????true??????
            }
        } catch (ElasticsearchException exception) {
            if (exception.status() == RestStatus.CONFLICT) {
                logger.error("????????????????????????????????? index:{} id:{}", index, id);
            }
            return false;
        } catch (Exception e) {
            logger.error("????????????????????? index:{} id:{} version:{}", index, id, version, e);
            return false;
        }

        StringBuilder failMsg = new StringBuilder();
        ReplicationResponse.ShardInfo shardInfo = response.getShardInfo();
        if (shardInfo.getTotal() != shardInfo.getSuccessful()) {
            failMsg.append("totalShard=").append(shardInfo.getTotal())
                    .append("successShard=").append(shardInfo.getSuccessful());
        }
        if (shardInfo.getFailed() > 0) {
            failMsg.append("failures are: [");
            for (ReplicationResponse.ShardInfo.Failure failure : shardInfo.getFailures()) {
                failMsg.append("{nodeId:").append(failure.nodeId()).append(",")
                        .append("reason:").append(failure.reason()).append("},");
            }
            failMsg.append("]");
        }
        logger.error("?????????????????? index:{} id:{} message:{}", index, id, failMsg.toString());
        return false;
    }

    /**
     * ??????????????????(???????????????)
     * @param index          ????????????
     * @param idSourceMap    id???JSON?????????????????????
     * @param idVersionMap   id?????????????????????
     * @return  ???????????????????????????id
     */
    public List<Long> batchSave(String index, Map<Long, String> idSourceMap, Map<Long, Long> idVersionMap){
        if(isEmpty(index)) {
            throw new RuntimeException("index????????????");
        }else if (idSourceMap == null || idSourceMap.isEmpty()) {
            throw new RuntimeException("idMapSource????????????");
        }

        BulkRequest request = new BulkRequest();
        idSourceMap.forEach((k,v) -> {
            Long version = idVersionMap != null ? idVersionMap.get(k) : null;
            IndexRequest idxRequest = new IndexRequest(index).id(k.toString()).source(v, XContentType.JSON);
            if(version != null){
                idxRequest.version(version).versionType(VersionType.EXTERNAL);
            }
            request.add(idxRequest);
        });

        BulkResponse responses;
        try {
            responses = restEsClient.bulk(request, RequestOptions.DEFAULT);
            if (! responses.hasFailures()) {
                return new ArrayList<>();
            }
        } catch (Exception e) {
            logger.error("???????????????????????? index:{} batchSize:{} idSourceMap:{}", index, idSourceMap.size(), Utils.toJson(idSourceMap), e);
            throw new RuntimeException("????????????????????????", e);
        }

        StringBuilder failMsg = new StringBuilder();
        List<Long> failIds = new ArrayList<>();
        for (BulkItemResponse itemResponse : responses.getItems()) {
            IndexResponse indexResponse = itemResponse.getResponse();
            if (!itemResponse.isFailed()
                    || DocWriteResponse.Result.CREATED.equals(indexResponse.getResult())
                    || DocWriteResponse.Result.UPDATED.equals(indexResponse.getResult())) {
                continue;
            }

            failIds.add(Long.valueOf(indexResponse.getId()));

            if(failMsg.length() > 0) {
                failMsg.append(",");
            }
            failMsg.append("{id:").append(indexResponse.getId()).append(",")
                    .append("cause:").append(itemResponse.getFailure().getCause()).append("}");
        }
        logger.error("???????????????????????????????????? index:{} message:{}", index, failMsg.toString());
        return failIds;
    }

    /**
     * ????????????????????????(???????????????????????????????????????????????????????????????)
     * 1??????????????????????????????????????????????????????
     * 2??????????????????????????????version???????????????????????????????????????
     * 3????????????????????????????????????version???????????????????????????
     * @param index         ????????????
     * @param id            id
     * @param sourceJson    JSON?????????????????????
     * @param version       ??????????????????
     * @return
     */
    public boolean save(String index, Long id, String sourceJson, Long version){
        if(isEmpty(index) || id == null || isEmpty(sourceJson)) {
            throw new RuntimeException("index???id???sourceJson ????????????");
        }

        IndexRequest request = new IndexRequest(index)
                .id(id.toString())
                .source(sourceJson, XContentType.JSON);
        if (version != null) {
            request.version(version).versionType(VersionType.EXTERNAL);
        }

        IndexResponse response;
        try {
            response = restEsClient.index(request, RequestOptions.DEFAULT);
            if (DocWriteResponse.Result.CREATED.equals(response.getResult())
                    || DocWriteResponse.Result.UPDATED.equals(response.getResult())) {
                return true;
            }
        } catch (Exception e) {
            logger.error("?????????????????? index:{} id:{} sourceJson:{}", index, id, sourceJson, e);
            return false;
        }

        StringBuilder sbf = new StringBuilder();
        ReplicationResponse.ShardInfo shardInfo = response.getShardInfo();
        if (shardInfo.getTotal() != shardInfo.getSuccessful()) {
            sbf.append("totalShard=").append(shardInfo.getTotal())
                    .append("successShard=").append(shardInfo.getSuccessful());
        }
        if (shardInfo.getFailed() > 0) {
            sbf.append("failures are: [");
            for (ReplicationResponse.ShardInfo.Failure failure : shardInfo.getFailures()) {
                sbf.append("{nodeId:").append(failure.nodeId()).append(",")
                        .append("reason:").append(failure.reason()).append("},");
            }
            sbf.append("]");
        }
        logger.error("?????????????????? index:{} id:{} message:{}", index, id, sbf.toString());
        return false;
    }

    /**
     * ?????????????????????????????????????????????
     * @param sourceIndex       ?????????
     * @param targetIndex       ????????????
     * @param externalVersion   ???????????????????????????????????????
     * @param listener          ????????????????????????
     */
    public void reindexAsync(String sourceIndex, String targetIndex, boolean externalVersion, ActionListener<BulkByScrollResponse> listener){
        if (!exists(sourceIndex)) {
            throw new RuntimeException("sourceIndex?????????");
        } else if (!exists(targetIndex)) {
            throw new RuntimeException("targetIndex?????????");
        }

        if(listener == null) {
            listener = new ActionListener<BulkByScrollResponse>() {
                @Override
                public void onResponse(BulkByScrollResponse bulkByScrollResponse) {
                    logger.info("reindex?????? sourceIndex={} targetIndex={}", sourceIndex, targetIndex);
                }
                @Override
                public void onFailure(Exception e) {
                    logger.error("reindex?????? sourceIndex={} targetIndex={}", sourceIndex, targetIndex, e);
                }
            };
        }

        ReindexRequest request = new ReindexRequest();
        request.setRefresh(true);
        request.setSourceIndices(sourceIndex);
        request.setDestIndex(targetIndex);
        if (externalVersion) {
            request.setDestVersionType(VersionType.EXTERNAL);
        }
        restEsClient.reindexAsync(request, RequestOptions.DEFAULT, listener);
    }

    /**
     * ????????????(????????????????????????????????????????????????)
     * @param index     ????????????
     * @param shards    ?????????
     * @param replicas  ????????????????????????
     * @param mappingProp   mappingProp
     */
    public boolean createIndex(String index, int shards, int replicas, Map<String, Map<String, Object>> mappingProp){
        if(isEmpty(index)) {
            throw new RuntimeException("index????????????");
        }else if(shards <= 0 || replicas <= 0){
            throw new RuntimeException("shards???replicas?????????0");
        }else if(mappingProp == null || mappingProp.isEmpty()){
            throw new RuntimeException("mappingProp????????????");
        }

        if (exists(index)) {
            return true;
        }

        CreateIndexRequest request = new CreateIndexRequest(index);
        request.settings(Settings.builder()
                .put("index.number_of_shards", shards)
                .put("index.number_of_replicas", replicas))
                .mapping(Collections.singletonMap("properties", mappingProp))
                .waitForActiveShards(ActiveShardCount.from(shards));
        try {
            CreateIndexResponse response = restEsClient.indices().create(request, RequestOptions.DEFAULT);
            return response.isShardsAcknowledged();
        } catch (Exception e) {
            logger.error("????????????????????? index:{} shards:{} replicas:{}", index, shards, replicas, e);
            return false;
        }
    }

    /**
     * ??????????????????????????????????????????????????????????????????
     * @param index     ?????????
     * @return
     */
    public boolean deleteIndex(String index){
        if(isEmpty(index)) {
            throw new RuntimeException("index????????????");
        }

        if(!exists(index)) {
            return true;
        }

        DeleteIndexRequest request = new DeleteIndexRequest(index);
        try {
            AcknowledgedResponse response = restEsClient.indices().delete(request, RequestOptions.DEFAULT);
            return response.isAcknowledged();
        } catch (Exception e) {
            logger.error("????????????????????? index:{}", index, e);
            return false;
        }
    }

    /**
     * ????????????????????????
     * @param index     ?????????
     * @return
     */
    public boolean exists(String index) {
        if (isEmpty(index)) return false;

        try {
            GetIndexRequest request = new GetIndexRequest(index);
            boolean exists = restEsClient.indices().exists(request, RequestOptions.DEFAULT);
            return exists;
        } catch (Exception e) {
            throw new RuntimeException("????????????????????????????????? index=" + index, e);
        }
    }

    /**
     * ??????index???ES??????mapping
     * @param index     ?????????
     * @return
     */
    public Map<String, Map<String, Object>> getMapping(String index) {
        if(isEmpty(index)) {
            throw new RuntimeException("index????????????");
        }

        Map<String, MappingMetaData> mappings;
        try {
            GetMappingsResponse mapping = restEsClient.indices().getMapping(new GetMappingsRequest().indices(index), RequestOptions.DEFAULT);
            mappings = mapping.mappings();
            if (mappings == null){
                return new HashMap<>();
            }
        } catch(Exception e) {
            throw new RuntimeException("????????????index???mapping?????????, index:" + index, e);
        }

        Map<String, Map<String, Object>> mapping = new HashMap<>();
        for(Map.Entry<String, MappingMetaData> entry : mappings.entrySet()){
            Map<String, Object> res = (Map<String, Object>) entry.getValue().sourceAsMap().get("properties");
            if(res == null) {
                continue;
            }

            for(Map.Entry<String, Object> entry1 : res.entrySet()){
                LinkedHashMap<String, Object> fieldMap = (LinkedHashMap<String, Object>) entry1.getValue();
                mapping.put(entry1.getKey(), fieldMap);
            }
        }
        return mapping;
    }

    /**
     * ????????????mapping???????????????????????????????????????????????????????????????
     * @param index         ?????????
     * @param mappingProp   ????????????????????????
     * @return
     */
    public boolean addMapping(String index, Map<String, Map<String, Object>> mappingProp) {
        if(isEmpty(index)) {
            throw new RuntimeException("index????????????");
        }else if(mappingProp == null || mappingProp.isEmpty()){
            throw new RuntimeException("mappingProp????????????");
        }

        Map<String, Map<String, Object>> filedTypeMapping = getMapping(index);
        if (filedTypeMapping == null) {
            filedTypeMapping = new HashMap<>();
        }

        boolean isNeedAddField = false;
        for (Map.Entry<String, Map<String, Object>> entry : mappingProp.entrySet()) {
            if (! filedTypeMapping.containsKey(entry.getKey())) {//????????????????????????mapping
                isNeedAddField = true;
                filedTypeMapping.put(entry.getKey(), entry.getValue());
            }
        }

        if(!isNeedAddField) {
            return false;
        }

        try {
            PutMappingRequest request = new PutMappingRequest(index);
            request.source(Collections.singletonMap("properties", filedTypeMapping));
            AcknowledgedResponse response = restEsClient.indices().putMapping(request, RequestOptions.DEFAULT);
            return response.isAcknowledged();
        } catch(Exception e) {
            logger.error("??????mapping?????? index:{}", index, e);
            return false;
        }
    }

    private SearchResponse executeQuery(EsQuery esQuery){
        paramCheck(esQuery, false);

        esQuery.doQueryParamCase(paramHelper);//????????????????????????

        //??????????????????????????????????????????????????????
        if(esQuery.getScrollMode() && Utils.isNotEmpty(esQuery.getScrollId())){
            SearchScrollRequest scrollRequest = new SearchScrollRequest(esQuery.getScrollId());
            scrollRequest.scroll(TimeValue.timeValueSeconds(esQuery.getScrollExpireSec()));
            try {
                return restEsClient.scroll(scrollRequest, RequestOptions.DEFAULT);
            } catch(Exception e) {
                throw new RuntimeException("???????????????????????????", e);
            }
        }

        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        sourceBuilder.timeout(new TimeValue(esQuery.getTimeout(), TimeUnit.MILLISECONDS));
        //???????????????????????????
        if(esQuery.getSelectFields() != null && esQuery.getSelectFields().length > 0){
            sourceBuilder.fetchSource(esQuery.getSelectFields(), null);
        }
        //??????????????????
        sourceBuilder.query(getQueryBuilder(esQuery));
        //??????????????????
        addSort(sourceBuilder, esQuery.getOrderBy());
        //???????????????????????????????????????????????? index???type
        SearchRequest searchRequest = new SearchRequest(esQuery.getIndex());
        searchRequest.source(sourceBuilder);
        //??????????????????
        if(esQuery.getScrollMode()){
            searchRequest.scroll(TimeValue.timeValueSeconds(esQuery.getScrollExpireSec()));
            sourceBuilder.size(esQuery.getPageSize());
        }else{
            int offset = (esQuery.getPageCurrent() - 1) * esQuery.getPageSize();
            sourceBuilder.from(offset).size(esQuery.getPageSize());
        }

        try {
            return restEsClient.search(searchRequest, RequestOptions.DEFAULT);
        } catch(Exception e) {
            throw new RuntimeException("?????????????????????", e);
        }
    }

    private SearchResponse executeAggregation(EsQuery esQuery){
        paramCheck(esQuery, true);
        //????????????????????????
        esQuery.doQueryParamCase(paramHelper);

        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        //??????????????????
        appendAggregation(sourceBuilder, esQuery);
        //????????????????????????
        sourceBuilder.query(getQueryBuilder(esQuery));

        SearchRequest searchRequest = new SearchRequest(esQuery.getIndex());
        searchRequest.source(sourceBuilder);

        try {
            return restEsClient.search(searchRequest, RequestOptions.DEFAULT);
        } catch(Exception e ){
            throw new RuntimeException("???????????????????????????", e);
        }
    }

    private QueryBuilder getQueryBuilder(EsQuery esQuery){
        Map<String, String> fieldMap = getESFieldTypeMapping(esQuery.getIndex());
        if(fieldMap == null || fieldMap.isEmpty()){
            throw new RuntimeException("es mapping not exist of index: " + esQuery.getIndex());
        }else if(! isEmpty(esQuery.getGroupBy()) && ! fieldMap.containsKey(esQuery.getGroupBy())){
            throw new RuntimeException("cannot use an not exist field to group by : " + esQuery.getGroupBy());
        }

        //???????????????????????????????????????????????????MatchAllQueryBuilder
        if (isMatchAll(esQuery)) {
            return QueryBuilders.matchAllQuery();
        }

        //?????????????????????????????????????????????combine???????????????????????????????????????????????????????????????????????????????????????sql?????? and ??????
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();

        //????????????(??????)
        if(isNotBlank(esQuery.getEqMap())){
            for(Map.Entry<String, Object> entry : esQuery.getEqMap().entrySet()){
                if(isNotEmpty(entry.getKey(), entry.getValue()) && fieldMap.containsKey(entry.getKey())){
                    queryBuilder.filter(QueryBuilders.termQuery(entry.getKey(), entry.getValue()));
                }
            }
        }

        //????????????(?????????)
        if(isNotBlank(esQuery.getNeqMap())) {
            for (Map.Entry<String, Object> entry : esQuery.getNeqMap().entrySet()) {
                if (isNotEmpty(entry.getKey(), entry.getValue()) && fieldMap.containsKey(entry.getKey())) {
                    queryBuilder.mustNot(QueryBuilders.termQuery(entry.getKey(), entry.getValue()));
                }
            }
        }

        //????????????????????????(in ??????)
        if(isNotBlank(esQuery.getInMap())){
            for(Map.Entry<String, Object[]> entry : esQuery.getInMap().entrySet()){
                if(isNotEmpty(entry.getKey(), entry.getValue()) && fieldMap.containsKey(entry.getKey())){
                    queryBuilder.filter(QueryBuilders.termsQuery(entry.getKey(), entry.getValue()));
                }
            }
        }

        //????????????(not in ??????)
        if(isNotBlank(esQuery.getNotInMap())) {
            for (Map.Entry<String, Object[]> entry : esQuery.getNotInMap().entrySet()) {
                if (isNotEmpty(entry.getKey(), entry.getValue()) && fieldMap.containsKey(entry.getKey())) {
                    queryBuilder.mustNot(QueryBuilders.termsQuery(entry.getKey(), entry.getValue()));
                }
            }
        }

        //??????????????????
        if(isNotBlank(esQuery.getGtMap()) || isNotBlank(esQuery.getGteMap())
                || isNotBlank(esQuery.getLtMap()) || isNotBlank(esQuery.getLteMap())){
            Set<String> keys = new HashSet<>();

            if(isNotBlank(esQuery.getGtMap())){
                keys.addAll(esQuery.getGtMap().keySet());
            }
            if(isNotBlank(esQuery.getGteMap())){
                keys.addAll(esQuery.getGteMap().keySet());
            }
            if(isNotBlank(esQuery.getLtMap())){
                keys.addAll(esQuery.getLtMap().keySet());
            }
            if(isNotBlank(esQuery.getLteMap())){
                keys.addAll(esQuery.getLteMap().keySet());
            }

            for(String key : keys){
                if(isEmpty(key) || ! fieldMap.containsKey(key)){
                    continue;
                }

                RangeQueryBuilder query = QueryBuilders.rangeQuery(key);
                Object valueGt = esQuery.getGtMap() == null ? null : esQuery.getGtMap().get(key);
                Object valueGte = esQuery.getGteMap() == null ? null : esQuery.getGteMap().get(key);
                Object valueLt = esQuery.getLtMap() == null ? null : esQuery.getLtMap().get(key);
                Object valueLte = esQuery.getLteMap() == null ? null : esQuery.getLteMap().get(key);

                if(valueGte != null && valueLte != null){
                    query.from(valueGte).to(valueLte);
                }else{
                    if(valueGt != null){
                        query.gt(valueGt);
                    }
                    if(valueGte != null){
                        query.gte(valueGte);
                    }
                    if(valueLt != null){
                        query.lt(valueLt);
                    }
                    if(valueLte != null){
                        query.lte(valueLte);
                    }
                }

                queryBuilder.filter(query);
            }
        }

        //????????????(ES????????????????????????????????????)
        if(isNotBlank(esQuery.getLikeMap())){
            for(Map.Entry<String, Object> entry : esQuery.getLikeMap().entrySet()){
                if (isNotEmpty(entry.getKey(), entry.getValue()) && fieldMap.containsKey(entry.getKey())) {
                    queryBuilder.filter(QueryBuilders.matchQuery(entry.getKey(), entry.getValue()));
                }
            }
        }

        //null??????????????????sql?????????where field is null
        if(isNotBlank(esQuery.getNullMap())){
            for(Map.Entry<String, Object> entry : esQuery.getNullMap().entrySet()){
                if (isNotEmpty(entry.getKey(), entry.getValue()) && fieldMap.containsKey(entry.getKey())) {
                    queryBuilder.mustNot(QueryBuilders.existsQuery(entry.getKey()));
                }
            }
        }

        //not null??????????????????sql?????????where field is not null
        if(isNotBlank(esQuery.getNotNullMap())){
            for(Map.Entry<String, Object> entry : esQuery.getNotNullMap().entrySet()){
                if (isNotEmpty(entry.getKey(), entry.getValue()) && fieldMap.containsKey(entry.getKey())) {
                    queryBuilder.filter(QueryBuilders.existsQuery(entry.getKey()));
                }
            }
        }

        return queryBuilder;
    }

    private void appendAggregation(SearchSourceBuilder sourceBuilder, EsQuery esQuery){
        Map<String, String> fieldMap = getESFieldTypeMapping(esQuery.getIndex());
        boolean isNeedTerms = Utils.isNotEmpty(esQuery.getGroupBy());
        TermsAggregationBuilder termsAggBuilder = null;
        if (isNeedTerms) {
            termsAggBuilder = AggregationBuilders.terms(esQuery.getGroupBy())
                    .field(esQuery.getGroupBy())
                    .size(MAX_GROUP_SIZE);//????????????????????????????????????????????????
        }

        Field[] fields = EsQuery.Aggregation.class.getDeclaredFields();
        for(Map.Entry<String, EsQuery.Aggregation> entry : esQuery.getAggMap().entrySet()){
            String aggField = entry.getKey();
            if(! fieldMap.containsKey(aggField)){ //ES????????????????????????????????????
                continue;
            }

            EsQuery.Aggregation agg = entry.getValue();
            for(Field field : fields){
                field.setAccessible(true);

                String name = field.getName();
                if(name.contains("this$") || "field".equals(name)){
                    continue;
                }

                Boolean value;
                try{
                    value = field.getBoolean(agg);
                }catch(Throwable e){
                    throw new RuntimeException("EsQuery.Aggregation ??????"+name+"???????????????????????????", e);
                }
                if(!value){
                    continue;
                }

                ValuesSourceAggregationBuilder aggBuilder;
                switch(name){
                    case "count":
                        aggBuilder = AggregationBuilders.count(fillFieldName(aggField, name)).field(aggField);
                        break;
                    case "sum":
                        aggBuilder = AggregationBuilders.sum(fillFieldName(aggField, name)).field(aggField);
                        break;
                    case "min":
                        aggBuilder = AggregationBuilders.min(fillFieldName(aggField, name)).field(aggField);
                        break;
                    case "max":
                        aggBuilder = AggregationBuilders.max(fillFieldName(aggField, name)).field(aggField);
                        break;
                    case "avg":
                        aggBuilder = AggregationBuilders.avg(fillFieldName(aggField, name)).field(aggField);
                        break;
                    default:
                        throw new RuntimeException("EsQuery.Aggregation ???????????????????????????" + name);
                }

                if(isNeedTerms){
                    termsAggBuilder.subAggregation(aggBuilder);
                }else{
                    sourceBuilder.aggregation(aggBuilder);
                }
            }
        }

        if(isNeedTerms){
            sourceBuilder.aggregation(termsAggBuilder);
        }
    }

    private void fillEsAggResult(AggResult aggResult, String groupValue, Iterator<Aggregation> iterator, EsQuery esQuery){
        while(iterator.hasNext()) {
            Aggregation aggEs = iterator.next();
            String fieldName = splitFieldName(aggEs.getName());
            if(esQuery.isWordCase()){
                fieldName = esQuery.isCamelCase() ? Utils.toCamelCase(fieldName) : Utils.toSnakeCase(fieldName);
            }

            EsAgg agg;
            Map<String, EsAgg> groupAggMap = aggResult.getAggDataMap().get(fieldName);//????????????????????????
            if(groupAggMap == null){
                groupAggMap = new HashMap<>();
                agg = new EsAgg();
                agg.setGroupValue(groupValue);
                groupAggMap.put(groupValue, agg);
                aggResult.getAggDataMap().put(fieldName, groupAggMap);
            }else if((agg = groupAggMap.get(groupValue)) == null){
                agg = new EsAgg();
                agg.setGroupValue(groupValue);
                groupAggMap.put(groupValue, agg);
            }

            switch(aggEs.getType()){
                case ValueCountAggregationBuilder.NAME:
                    agg.setCount(((ParsedValueCount) aggEs).getValue());
                    break;
                case MaxAggregationBuilder.NAME:
                    agg.setMax(BigDecimal.valueOf(((ParsedMax) aggEs).getValue()));
                    break;
                case MinAggregationBuilder.NAME:
                    agg.setMin(BigDecimal.valueOf(((ParsedMin) aggEs).getValue()));
                    break;
                case SumAggregationBuilder.NAME:
                    agg.setSum(BigDecimal.valueOf(((ParsedSum) aggEs).getValue()));
                    break;
                case AvgAggregationBuilder.NAME:
                    agg.setAvg(BigDecimal.valueOf(((ParsedAvg) aggEs).getValue()));
                    break;
                default:
                    throw new RuntimeException("???????????????????????????" + aggEs.getType());
            }
        }
    }

    /**
     * ????????????????????????????????????
     * @param esQuery
     * @return
     */
    private Class getReturnClass(EsQuery esQuery){
        if(Utils.isEmpty(esQuery.getReturnClassName())){
            return HashMap.class;
        }else{
            try{
                return Utils.getClass(esQuery.getReturnClassName());
            }catch (ClassNotFoundException e){
                throw new RuntimeException("ClassNotFoundException " + e.getMessage());
            }
        }
    }

    /**
     * ??????????????????
     * @param searchBuilder
     * @param sortColumns
     */
    protected void addSort(SearchSourceBuilder searchBuilder, String sortColumns){
        if(Utils.isEmpty(sortColumns)){
            return;
        }

        String[] sortColumnArray = sortColumns.split(",");
        for(int i=0; i<sortColumnArray.length; i++){
            String[] sortColumn = sortColumnArray[i].split(" ");
            if(sortColumn.length > 1){
                searchBuilder.sort(sortColumn[0], SortOrder.fromString(sortColumn[sortColumn.length - 1]));
            }else{
                searchBuilder.sort(sortColumn[0], SortOrder.DESC);
            }
        }
    }

    private void paramCheck(EsQuery esQuery, boolean aggMapMust){
        if(esQuery == null){
            throw new RuntimeException("esQuery????????????");
        }else if(Utils.isEmpty(esQuery.getIndex())){
            throw new RuntimeException("index????????????");
        }else if(esQuery.getPageSize() <= 0 || esQuery.getPageCurrent() <= 0){
            throw new RuntimeException("pageCurrent???pageSize????????????0");
        }else if(aggMapMust && (esQuery.getAggMap() == null || esQuery.getAggMap().isEmpty())){
            throw new RuntimeException("aggMap????????????");
        }
    }

    private boolean isNotEmpty(String key, Object value){
        return ! (isEmpty(key) || (value == null || value.toString().trim().length() <= 0));
    }
    private boolean isNotEmpty(String key, Object[] values){
        return ! (isEmpty(key) || (values == null || values.length <= 0));
    }
    private boolean isEmpty(String key){
        return (key == null || key.trim().length() <= 0);
    }
    private boolean isNotBlank(Map<String, ?> map){
        return !isBlank(map);
    }
    private boolean isBlank(Map<String, ?> map){
        return map == null || map.isEmpty();
    }

    public boolean isMatchAll(EsQuery esQuery) {
        return isBlank(esQuery.getEqMap()) && isBlank(esQuery.getNeqMap())
                && isBlank(esQuery.getGtMap()) && isBlank(esQuery.getGteMap())
                && isBlank(esQuery.getLtMap()) && isBlank(esQuery.getLteMap())
                && isBlank(esQuery.getLikeMap()) && isBlank(esQuery.getInMap())
                && isBlank(esQuery.getNotInMap());
    }

    private boolean isHashMap(Class clz){
        return HashMap.class.getName().equals(clz.getName());
    }

    private boolean isString(Class clz){
        return String.class.getName().equals(clz.getName());
    }

    private String fillFieldName(String field, String suffix){
        return field + "|" + suffix;
    }

    private String splitFieldName(String field){
        return field.split("\\|")[0];
    }

    /**
     * key???????????????????????????
     * @param entryMap
     * @param isCamelCase
     * @return
     */
    private Map<String, Object> resultWordCase(Map<String, Object> entryMap, boolean isCamelCase){
        Map<String, Object> resultMap = new HashMap<>();
        for(Map.Entry<String, Object> entry : entryMap.entrySet()) {
            String key = isCamelCase ? Utils.toCamelCase(entry.getKey()) : Utils.toSnakeCase(entry.getKey());
            resultMap.put(key, entry.getValue());
        }
        return resultMap;
    }

    /**
     * ??????index???ES???????????????????????????key???????????????value???????????????????????????
     * @param index
     * @return
     */
    private Map<String, String> getESFieldTypeMapping(String index){
        if(cache != null && cache.getIfPresent(index) != null){
            return cache.getIfPresent(index);
        }

        Map<String, Map<String, Object>> mapping = getMapping(index);

        Map<String, String> fieldTypeMap = new HashMap<>();
        for(Map.Entry<String, Map<String, Object>> entry : mapping.entrySet()){
            if(Utils.isNotEmpty(entry.getKey())){
                fieldTypeMap.put(entry.getKey(), (String) entry.getValue().get("type"));
            }
        }
        cache.put(index, fieldTypeMap);
        return fieldTypeMap;
    }

    private void clearScrollAsync(String scrollId){
        ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
        clearScrollRequest.addScrollId(scrollId);
        getRestEsClient().clearScrollAsync(clearScrollRequest, RequestOptions.DEFAULT, new ActionListener<ClearScrollResponse>() {
            @Override
            public void onResponse(ClearScrollResponse clearScrollResponse) {
                if(clearScrollResponse.isSucceeded()){
                    logger.info("?????????????????? scrollId={} ", scrollId);
                }else{
                    logger.warn("?????????????????? scrollId={} status={} ", scrollId, clearScrollResponse.status().name());
                }
            }
            @Override
            public void onFailure(Exception e) {
                logger.error("?????????????????? scrollId={} Exception={} ", scrollId, e.getMessage());
            }
        });
    }

    public void destroy(){
        try{
            this.getRestEsClient().close();
        }catch(Throwable e){
        }
    }

    private class InnerParamHelper implements EsQuery.ParamHelper {
        public boolean isNotEmpty(String val){
            return Utils.isNotEmpty(val);
        }

        public String toSnakeCase(String val){
            return Utils.toSnakeCase(val);
        }

        public String toCamelCase(String val){
            return Utils.toCamelCase(val);
        }
    }

    /**
     * ?????????????????????????????????
     */
    private class AggResult {
        /**
         * ????????????
         */
        private String groupField;
        /**
         * ????????????????????????key????????????????????????key???group by????????????????????????????????????value??????????????????????????????
         */
        Map<String, Map<String, EsAgg>> aggDataMap = new HashMap<>();

        public String getGroupField() {
            return groupField;
        }

        public void setGroupField(String groupField) {
            this.groupField = groupField;
        }

        public Map<String, Map<String, EsAgg>> getAggDataMap() {
            return aggDataMap;
        }

        public void setAggDataMap(Map<String, Map<String, EsAgg>> aggDataMap) {
            this.aggDataMap = aggDataMap;
        }

        public EsAggResult toEsAggResult(){
            EsAggResult result = new EsAggResult();
            result.setGroupField(this.groupField);
            for(Map.Entry<String, Map<String, EsAgg>> entry : aggDataMap.entrySet()){
                String key = entry.getKey();//?????????
                Map<String, EsAgg> valueMap = entry.getValue();//???????????????????????????

                List<EsAgg> aggList = new ArrayList<>();
                for(Map.Entry<String, EsAgg> aggEntry : valueMap.entrySet()){
                    EsAgg aggDto = aggEntry.getValue();
                    if (DEFAULT_GROUP_VALUE.equals(aggDto.getGroupValue())) {
                        aggDto.setGroupValue(null);
                    }
                    aggList.add(aggDto);
                }
                result.getAggResults().put(key, aggList);
            }
            return result;
        }
    }
}

