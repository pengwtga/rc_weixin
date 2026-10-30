package com.rightcapital.notify.config;

import com.rightcapital.notify.common.crypto.PayloadCipher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 载荷保护组件的装配（D10）。
 *
 * <p>把密钥解析与组件构造放在配置层，业务代码只依赖 {@link PayloadCipher} 这个窄接口 ——
 * 将来接 KMS、换密钥版本，改动收敛在这个文件里，投递逻辑不动。</p>
 */
@Configuration
public class CryptoConfig {

    @Bean
    public PayloadCipher payloadCipher(NotificationProperties properties) {
        return new PayloadCipher(properties.crypto().dataKeyBytes());
    }
}
