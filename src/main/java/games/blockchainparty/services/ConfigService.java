package games.blockchainparty.services;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Keys;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAll;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.util.Tuple;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.blockchainparty.config.ConfigProperties;
import games.blockchainparty.config.Network;
import games.blockchainparty.services.model.Policy;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

@Service
@Data
@Slf4j
public class ConfigService {

    public static final TextEncryptor TEXT_ENCRYPTOR = Encryptors.text("Hallelujah", "49737261656C6943617264526E6F436F6D6D752E69747944756469");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConfigProperties configProperties;
    private final FilesService filesService;
    private Policy policy;
    private Account account;

    @Autowired
    public ConfigService(ConfigProperties configProperties, FilesService filesService) {
        this.configProperties = configProperties;
        this.filesService = filesService;
        loadAccount();
        loadPolicy();
    }

    private void loadAccount() {
        String fileName = configProperties.getNetwork().getValue()+"_account";
        if (filesService.exists(fileName)) { //Policy File Exists, Read it.
            String mnemonic = TEXT_ENCRYPTOR.decrypt(filesService.read(fileName));
            if (configProperties.getNetwork() == Network.TEST_NET) {
                account = new Account(Networks.testnet(), mnemonic);
            } else {
                account = new Account(Networks.mainnet(), mnemonic);
            }
        } else { //Policy File Missing, Create Policy and Save it for persistency.
            account = createAccount();
            filesService.writeToFile(fileName, TEXT_ENCRYPTOR.encrypt(account.mnemonic()));
        }
        log.info("Wallet Address: "+account.baseAddress());
    }

    private Account createAccount() {
        Account account;
        if (configProperties.getNetwork() == Network.TEST_NET) {
            account =  new Account(Networks.testnet());
        } else {
            account =  new Account(Networks.mainnet());
        }
        return account;
    }

    @SneakyThrows
    private void loadPolicy() {
        String fileName = configProperties.getNetwork().getValue()+"_policy";
        if (filesService.exists(fileName)) { //Policy File Exists, Read it.
            String policyJson = TEXT_ENCRYPTOR.decrypt(filesService.read(fileName));
            this.policy = objectMapper.readValue(policyJson, Policy.class);
        } else { //Policy File Missing, Create Policy and Save it for persistency.
            this.policy = createPolicy();
            String policyJson = objectMapper.writeValueAsString(policy);
            filesService.writeToFile(fileName, TEXT_ENCRYPTOR.encrypt(policyJson));
        }
    }

    @SneakyThrows
    private Policy createPolicy() {
        Policy policy = new Policy();
        Tuple<ScriptPubkey, Keys> tuple = ScriptPubkey.createWithNewKey();
        ScriptPubkey scriptPubkey = tuple._1;
        policy.setPolicyKeys(tuple._2);
        policy.setScriptAll(new ScriptAll().addScript(scriptPubkey));
        log.info("Created New Policy: "+policy.getScriptAll().getPolicyId());
        return policy;
    }

    public Network getNetwork() {
        return configProperties.getNetwork();
    }

    public String getBlockFrostApiKey() {
        return configProperties.getBlockfrostApiKey();
    }
}