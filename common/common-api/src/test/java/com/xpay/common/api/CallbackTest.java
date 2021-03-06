package com.xpay.common.api;

import com.xpay.common.api.params.CallbackParam;
import com.xpay.common.api.dto.CallbackResp;
import com.xpay.common.api.dto.SecretKey;
import com.xpay.common.api.utils.CallbackUtil;
import com.xpay.common.statics.enums.common.SignTypeEnum;
import com.xpay.common.statics.exception.BizException;
import com.xpay.common.utils.JsonUtil;
import com.xpay.common.utils.RandomUtil;

import java.util.HashMap;
import java.util.Map;

public class CallbackTest {
    //DEBUG
    private static String md5Key = "B87F24EC25310CD4402A360CD150BF6B";
    private static String platPrivateKey = "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAKbGElB0faaAnSojqjfb3w7vT4wwOcCppHvn17X6ohhgoh3vvJLQMf7fZawDJPRLtCoHZp56uZBNFh6nze1rF+ljrT8UoRcky3O7CBMrocycqP/Lpja2y+pgctHClymlq0lIjTG2tBCBOhs5tjQ5dSA2F9rsUmVPlAjRZY8at0fnAgMBAAECgYEAhcTENeJqUp5A8ebvhqSGsz0Cykh4Wm/37ibVYDMrx2/jOS3tTLlQEMZxj9ppzsXWOgv7pMx9gSBDyM0CIRhQcWdvqsv8InpDXX7sz+JqYQB2CbjBrLX9CDhHpD+RQA/r2G9DAoLhZKoOjNPJh5WNrGtiH+DVnpEWF5SXnjfSzAECQQDlM+gW+7QdukDbBk+FlAh0zExnS8rzhpEa5+5EWiysWnHwjw2GOhqfFk7trScniaBbv4fv9WBj7OpJ5SHcUM4BAkEAukWmFLXojPfJ0siNflBBBI36CXBGbmIzynHg9a/CvOY51+QHl+kIQC7RwS5N94X9YtBN8hDYkjKpCG7syodl5wJAAPuU/iw8HHiE+KtxQdhdpOqPVU4M47hq/NuLuP1N/bsxi9+BJlcvcAkvc3NvnIrJhjsvAQdjT2pfost5trEeAQJAIpn5hfNcpYMJ/JvAnOwvh7cP8Vzn2G1pjXul/D2QASMLL61uM6vYGoQX9rixRv+e2BI1yHeUo2PBvo1Mczq/lQJBAIPq5QPJxvRZwfhIc7l+nzfBuhhoDBj09SkGmBpf6m/pJa/tOqgMP57OLLclU8qyZiEsXkpMpAoHWGdjFZairbo=";
    private static String mchPublicKey = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCt8f64aJf4fHGXcHZZi/6MG7BpxqfNcVo8zV6lj4+/THj0usYORKvfzjBYc9fX/P+1DWmJ0jONBg11OVwGcCmoSHNWR4lDhf/JI3QnYm3rgKITsv598fSBPiZDL3uSbeoCuyJlAfrZcRiv4b7NGUt6Fr/wuRStZuEHwC9sNwAaiwIDAQAB";
    private static String mchNo = "888800000001";
    private static String signType = String.valueOf(SignTypeEnum.RSA.getValue());


    public static void main(String[] args){
        int rand = RandomUtil.getInt(2);
        if(rand % 2 == 0){
            sync();
        }else{
            async();
        }
    }

    private static void sync(){
        String url = "127.0.0.1:8000/demo/callback";
        CallbackParam<Map> request = new CallbackParam();
        request.setMchNo(mchNo);
        request.setRandStr(RandomUtil.get32LenStr());
        request.setSignType(signType);
        request.setSecKey(RandomUtil.get16LenStr());

        Map<String, String> data = new HashMap<>();
        data.put("sync_key_01", "value_01");
        data.put("sync_key_02", "value_??????");
        request.setData(data);

        SecretKey secretKey = getSecretKey();
        CallbackResp resp = CallbackUtil.requestSync(url, request, secretKey);
        if(resp.isNeedRetry()){
            System.out.println("sync ???????????? CallbackResp???" + JsonUtil.toJsonWithNull(resp));
        }else{
            System.out.println("sync ???????????? CallbackResp???" + JsonUtil.toJsonWithNull(resp));
        }
    }

    private static void async(){
        String url = "127.0.0.1:8000/demo/callback";
        CallbackParam<Map> request = new CallbackParam();
        request.setMchNo(mchNo);
        request.setRandStr(RandomUtil.get32LenStr());
        request.setSignType(signType);
        request.setSecKey(RandomUtil.get16LenStr());

        Map<String, String> data = new HashMap<>();
        data.put("async_key_01", "value_01");
        data.put("async_key_02", "value_??????");
        request.setData(data);

        SecretKey secretKey = getSecretKey();
        CallbackUtil.requestAsync(url, request, secretKey, new CallbackUtil.Callback(){
            @Override
            public void onResponse(CallbackResp resp) {
                if(resp.isNeedRetry()){
                    System.out.println("async ???????????? CallbackResp???" + JsonUtil.toJsonWithNull(resp));
                }else{
                    System.out.println("async ???????????? CallbackResp???" + JsonUtil.toJsonWithNull(resp));
                }
            }

            @Override
            public void onError(CallbackResp resp, Throwable e) {
                System.out.println("async ???????????? CallbackResp???" + JsonUtil.toJsonWithNull(resp) + "???Throwable???" + e.getMessage());
            }
        });
    }

    private static SecretKey getSecretKey(){
        SecretKey secretKey = new SecretKey();

        //?????????????????????????????????????????????????????????????????????
        if (String.valueOf(SignTypeEnum.MD5.getValue()).equals(signType)) {
            secretKey.setReqSignKey(md5Key);
            secretKey.setRespVerifyKey(md5Key);
            secretKey.setSecKeyDecryptKey(md5Key);
            secretKey.setSecKeyEncryptKey(md5Key);
        } else if (String.valueOf(SignTypeEnum.RSA.getValue()).equals(signType)) {
            secretKey.setReqSignKey(platPrivateKey);//?????????????????????
            secretKey.setRespVerifyKey(mchPublicKey);//?????????????????????
            secretKey.setSecKeyEncryptKey(mchPublicKey);//?????????????????????
            secretKey.setSecKeyDecryptKey(platPrivateKey);//?????????????????????
        } else {
            throw new BizException("????????????????????????: " + signType);
        }
        return secretKey;
    }
}
