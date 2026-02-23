package io.github.uou_capstone.aiplatform.util;

import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 해시 유틸리티
 * 
 * MD5 해시 생성 유틸리티
 * - 강의 내용의 해시 생성 (캐시 키용)
 * - Profile 캐싱 키 생성
 */
public class HashUtil {

    /**
     * MD5 해시 생성
     * 
     * @param content 해시할 내용
     * @return MD5 해시 문자열
     */
    public static String generateMD5Hash(String content) {
        return DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8));
    }
}
