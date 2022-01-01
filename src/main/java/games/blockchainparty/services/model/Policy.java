package games.blockchainparty.services.model;

import com.bloxbean.cardano.client.crypto.Keys;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAll;
import lombok.Data;

@Data
public class Policy {

    private ScriptAll scriptAll;
    private Keys policyKeys;

    public Policy() {
        //Empty Constructor
    }
}