package com.xpay.common.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.impl.PublicClaims;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class JWTUtil {
    public final static String IIS = "xpay_sys";

    /**
     * 生成一个token
     * @param subject       jwt所面向的用户
     * @param audience      接收jwt的一方
     * @param expireSec     过期时间(秒)
     * @param secretKey     密钥
     * @param claims        其他信息
     * @return
     */
    public static String createToken(String subject, String audience, Integer expireSec, String secretKey, Map<String, String> claims) throws RuntimeException {
        try {
            JWTCreator.Builder builder = JWT.create()
                    .withIssuer(IIS)//jwt签发者
                    .withSubject(subject)//jwt所面向的用户
                    .withAudience(audience)//接收jwt的一方
                    .withIssuedAt(new Date(System.currentTimeMillis()))//jwt的签发时间
                    .withJWTId(RandomUtil.get32LenStr());//jwt的唯一身份标识，主要用来作为一次性token,从而回避重放攻击ll
            if(expireSec != null){
                builder.withExpiresAt(new Date(System.currentTimeMillis() + expireSec * 1000L));//过期时间
            }
            Algorithm algorithm = Algorithm.HMAC256(secretKey);
            claims.forEach(builder::withClaim);
            return builder.sign(algorithm);
        } catch (IllegalArgumentException | JWTCreationException e) {
            throw new RuntimeException("生成token失败", e);
        }
    }

    /**
     * 验证jwt，并返回数据
     * @param token         待验证的token
     * @param secretKey     密钥
     * @return
     * @throws RuntimeException
     */
    public static Map<String, String> verifyToken(String token, String secretKey) throws RuntimeException {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secretKey);
            JWTVerifier verifier = JWT.require(algorithm)
                    .withIssuer()
                    .build();
            DecodedJWT jwt = verifier.verify(token);
            Map<String, Claim> map = jwt.getClaims();
            Map<String, String> resultMap = new HashMap<>(map.size());
            map.forEach((k, v) -> resultMap.put(k, v.asString()));
            return resultMap;
        } catch (JWTVerificationException e) {
            throw new RuntimeException("Token校验失败", e);
        }
    }

    public static String getSubject(Map<String, String> claims){
        if(claims != null){
            return claims.get(PublicClaims.SUBJECT);
        }
        return null;
    }

    public static String getAudience(Map<String, String> claims){
        if(claims != null){
            return claims.get(PublicClaims.AUDIENCE);
        }
        return null;
    }
}
