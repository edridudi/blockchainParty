package games.blockchainparty.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "cardano")
public class ConfigProperties {

    private Network network;
    private String blockfrostApiKey;
}