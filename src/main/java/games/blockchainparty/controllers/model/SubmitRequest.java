package games.blockchainparty.controllers.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SubmitRequest {

    @JsonProperty("number")
    private Integer number;

    @JsonProperty("secret_key")
    private String secretKey;

    @JsonProperty("tx")
    private String txHash;
}