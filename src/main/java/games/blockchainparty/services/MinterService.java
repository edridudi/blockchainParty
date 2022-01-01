package games.blockchainparty.services;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.helper.UtxoSelectionStrategy;
import com.bloxbean.cardano.client.backend.api.helper.impl.DefaultUtxoSelectionStrategyImpl;
import com.bloxbean.cardano.client.backend.api.helper.impl.OnlyAdaUtxoSelectionStrategyImpl;
import com.bloxbean.cardano.client.backend.exception.ApiException;
import com.bloxbean.cardano.client.backend.exception.InsufficientBalanceException;
import com.bloxbean.cardano.client.backend.factory.BackendFactory;
import com.bloxbean.cardano.client.backend.impl.blockfrost.common.Constants;
import com.bloxbean.cardano.client.backend.model.Genesis;
import com.bloxbean.cardano.client.backend.model.ProtocolParams;
import com.bloxbean.cardano.client.backend.model.Result;
import com.bloxbean.cardano.client.backend.model.Utxo;
import com.bloxbean.cardano.client.common.MinAdaCalculator;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.model.TransactionDetailsParams;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.util.AssetUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.util.Tuple;
import games.blockchainparty.config.Network;
import games.blockchainparty.controllers.model.SubmitRequest;
import games.blockchainparty.services.model.Policy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.client.common.CardanoConstants.LOVELACE;
import static com.bloxbean.cardano.client.common.CardanoConstants.ONE_ADA;

@Service
@Slf4j
public class MinterService {

    private final ConfigService configService;
    private final BackendService backendService;
    private ProtocolParams protocolParams;

    @SneakyThrows
    public MinterService(ConfigService configService) {
        this.configService = configService;
        if (configService.getNetwork() == Network.TEST_NET) {
            backendService = BackendFactory.getBlockfrostBackendService(Constants.BLOCKFROST_TESTNET_URL, configService.getBlockFrostApiKey());
            Result<Genesis> genesisResult = backendService.getNetworkInfoService().getNetworkInfo();
            if (genesisResult.isSuccessful()) {
                int networkMagic = genesisResult.getValue().getNetworkMagic();
                log.info("Testnet Magic: "+networkMagic);
            } else {
                log.error("Genesis Result is Unsuccessful!");
                System.exit(1);
            }
        } else {
            backendService = BackendFactory.getBlockfrostBackendService(Constants.BLOCKFROST_MAINNET_URL, configService.getBlockFrostApiKey());
        }
        try {
            Result<ProtocolParams> protocolParamsResult = backendService.getEpochService().getProtocolParameters();
            if (protocolParamsResult.isSuccessful()) {
                protocolParams = protocolParamsResult.getValue();
            } else {
                log.error("Failed to Fetch ProtocolParams Object.");
            }
        } catch (ApiException e) {
            log.error(e.getMessage(), e);
        }
    }

    public String createTransaction(String userAddr, Integer number) throws ApiException, CborSerializationException {
        Policy policy = configService.getPolicy();
        long ttl = backendService.getBlockService().getLastestBlock().getValue().getSlot() + 2000;
        TransactionDetailsParams detailsParams = TransactionDetailsParams.builder().ttl(ttl).build();
        List<MultiAsset> multiAssetList = Collections.singletonList(createMultiAsset(policy.getScriptAll().getPolicyId()));

        BigInteger minAmount = createDummyOutputAndCalculateMinAdaForTxnOutput(userAddr, multiAssetList, protocolParams);
        BigInteger amountToTransfer = BigInteger.ZERO;

        List<Utxo> utxos = getUtxos(userAddr, LOVELACE, amountToTransfer.add(ONE_ADA.multiply(BigInteger.valueOf(2))), Collections.emptySet());
        if (utxos.isEmpty())
            throw new InsufficientBalanceException("Not enough utxos found to cover balance : " + amountToTransfer + " lovelace");

        TransactionOutput change = TransactionOutput
                .builder()
                .address(userAddr)
                .value(Value.builder().coin(BigInteger.ZERO)
                        .multiAssets(new ArrayList<>())
                        .build())
                .build();

        List<TransactionInput> inputs = new ArrayList<>();
        for (Utxo utxo : utxos) {
            TransactionInput input = TransactionInput.builder()
                    .transactionId(utxo.getTxHash())
                    .index(utxo.getOutputIndex()).build();
            inputs.add(input);

            copyUtxoValuesToChangeOutput(change, utxo);
        }

        //Deduct fee + minCost in a MA output
        BigInteger remainingAmount = change.getValue().getCoin().subtract(amountToTransfer.add(minAmount));
        change.getValue().setCoin(remainingAmount); //deduct requirement amt (fee + min amount)

        //Check if minimum Ada is not met. Topup
        //Transaction will fail if minimum ada not there. So try to get some additional utxos
        verifyMinAdaInOutputAndUpdateIfRequired(inputs, change, detailsParams, utxos, protocolParams);

        TransactionOutput mintedTransactionOutput = new TransactionOutput();
        mintedTransactionOutput.setAddress(userAddr);
        Value value = Value.builder()
                .coin(minAmount)
                .multiAssets(new ArrayList<>())
                .build();
        mintedTransactionOutput.setValue(value);
        for (MultiAsset ma : multiAssetList) {
            mintedTransactionOutput.getValue().getMultiAssets().add(ma);
        }

        List<TransactionOutput> outputs = Arrays.asList(change, mintedTransactionOutput);

        TransactionBody body = TransactionBody.builder().inputs(inputs)
                .outputs(outputs)
                .fee(BigInteger.valueOf(170000))
                .ttl(detailsParams.getTtl())
                .validityStartInterval(detailsParams.getValidityStartInterval())
                .mint(multiAssetList)
                .build();

        // Sign ->
        TransactionWitnessSet transactionWitnessSet = new TransactionWitnessSet();
        transactionWitnessSet.getNativeScripts().add(policy.getScriptAll());

        Transaction transaction = Transaction.builder().body(body).witnessSet(transactionWitnessSet).metadata(getMetaData(policy.getScriptAll().getPolicyId())).build();

        Account signer1;
        Account signer2;

        if (configService.getNetwork() == Network.TEST_NET) {
            //Signers
            String signerAcc1Mnemonic = "around submit turtle canvas friend remind push vehicle debate drop blouse piece obvious crane tone avoid aspect power milk eye brand cradle tide wrist";
            String signerAcc2Mnemonic = "prison glide olympic diamond rib payment crucial ski vintage example dinner matrix cruise upper antenna surge drink divorce brother half figure skate jar stand";
            signer1 = new Account(Networks.testnet(), signerAcc1Mnemonic);
            signer2 = new Account(Networks.testnet(), signerAcc2Mnemonic);
        } else {
            String signerAcc1Mnemonic = "asset fringe permit rural balance emotion zone fatigue roast thought nurse reason fame recall forget recycle message hospital grass defense device sword insane myself";
            String signerAcc2Mnemonic = "energy noise stamp lady husband gym dream hand float actual lady end economy comic excuse mango junk pencil fold galaxy weird repair visual receive";
            signer1 = new Account(Networks.mainnet(), signerAcc1Mnemonic);
            signer2 = new Account(Networks.mainnet(), signerAcc2Mnemonic);
        }

        //Sign the transaction. so that we get the actual size of the transaction to calculate the fee
        Transaction signTxn = signer1.sign(transaction); //cbor encoded bytes in Hex format
        signTxn = signer2.sign(signTxn);

        if (policy.getPolicyKeys() != null) {
            signTxn = TransactionSigner.INSTANCE.sign(signTxn, policy.getPolicyKeys().getSkey());
        }
        BigInteger fee = backendService.getFeeCalculationService().calculateFee(signTxn);

        body.setFee(fee);
        //Final change amount after amountToTransfer + fee
        change.getValue().setCoin(change.getValue().getCoin().subtract(fee));

        return transaction.serializeToHex();
    }

    public String submitTransaction(SubmitRequest submitRequest) throws CborDeserializationException, CborSerializationException, ApiException {
        Transaction transaction = Transaction.deserialize(HexUtil.decodeHexString(submitRequest.getTxHash()));
        Policy policy = configService.getPolicy();
        transaction = TransactionSigner.INSTANCE.sign(transaction, policy.getPolicyKeys().getSkey());
        transaction = configService.getAccount().sign(transaction);

        transaction.setValid(true);

        Result<String> result = backendService.getTransactionService().submitTransaction(transaction.serialize());
        if (result.isSuccessful()) {
            return result.getValue();
        } else {
            return null;
        }
    }

    private Metadata getMetaData(String policyId) {
        CBORMetadataMap cborMetadataMap3 = new CBORMetadataMap();
        CBORMetadataMap cborMetadataMap2 = new CBORMetadataMap();
        cborMetadataMap2.put("test", get2ndLevelMetaData());
        cborMetadataMap3.put(policyId, cborMetadataMap2);
        return new CBORMetadata().put(BigInteger.valueOf(721), cborMetadataMap3);
    }

    private CBORMetadataMap get2ndLevelMetaData() {
        CBORMetadataMap cborMetadataMap = new CBORMetadataMap();
        cborMetadataMap.put("name", "test");
        return cborMetadataMap;
    }

    private void verifyMinAdaInOutputAndUpdateIfRequired(List<TransactionInput> inputs, TransactionOutput transactionOutput, TransactionDetailsParams detailsParams, Collection<Utxo> excludeUtxos, ProtocolParams protocolParams) throws ApiException {
        BigInteger minRequiredLovelaceInOutput = new MinAdaCalculator(protocolParams).calculateMinAda(transactionOutput);
        //Create another copy of the list
        List<Utxo> ignoreUtxoList = new ArrayList<>(excludeUtxos);
        while (transactionOutput.getValue().getCoin() != null && minRequiredLovelaceInOutput.compareTo(transactionOutput.getValue().getCoin()) == 1) {
            //Get utxos
            List<Utxo> additionalUtxos = getUtxos(transactionOutput.getAddress(), LOVELACE, minRequiredLovelaceInOutput, new HashSet(ignoreUtxoList));
            if (additionalUtxos == null || additionalUtxos.size() == 0) {
                if (log.isDebugEnabled()) {
                    log.debug("Not enough utxos found to cover minimum lovelace in an output");
                }
                break;
            }
            if (log.isDebugEnabled()) log.debug("Additional Utoxs found: " + additionalUtxos);
            for (Utxo addUtxo : additionalUtxos) {
                TransactionInput addTxnInput = TransactionInput.builder().transactionId(addUtxo.getTxHash()).index(addUtxo.getOutputIndex()).build();
                inputs.add(addTxnInput);
                //Update change output
                copyUtxoValuesToChangeOutput(transactionOutput, addUtxo);
            }
            ignoreUtxoList.addAll(additionalUtxos);
            //Calculate final minReq balance in output, if still doesn't satisfy, continue again
            minRequiredLovelaceInOutput = new MinAdaCalculator(protocolParams).calculateMinAda(transactionOutput);
        }
    }

    /**
     * Copy utxo content to TransactionOutput
     */
    protected void copyUtxoValuesToChangeOutput(TransactionOutput changeOutput, Utxo utxo) {
        utxo.getAmount().forEach(utxoAmt -> { //For each amt in utxo
            String utxoUnit = utxoAmt.getUnit();
            BigInteger utxoQty = utxoAmt.getQuantity();
            if (utxoUnit.equals(LOVELACE)) {
                BigInteger existingCoin = changeOutput.getValue().getCoin();
                if (existingCoin == null) existingCoin = BigInteger.ZERO;
                changeOutput.getValue().setCoin(existingCoin.add(utxoQty));
            } else {
                Tuple<String, String> policyIdAssetName = AssetUtil.getPolicyIdAndAssetName(utxoUnit);

                //Find if the policy id is available
                Optional<MultiAsset> multiAssetOptional =
                        changeOutput.getValue().getMultiAssets().stream().filter(ma -> policyIdAssetName._1.equals(ma.getPolicyId())).findFirst();
                if (multiAssetOptional.isPresent()) {
                    Optional<Asset> assetOptional = multiAssetOptional.get().getAssets().stream()
                            .filter(ast -> policyIdAssetName._2.equals(ast.getName()))
                            .findFirst();
                    if (assetOptional.isPresent()) {
                        BigInteger changeVal = assetOptional.get().getValue().add(utxoQty);
                        assetOptional.get().setValue(changeVal);
                    } else {
                        Asset asset = new Asset(policyIdAssetName._2, utxoQty);
                        multiAssetOptional.get().getAssets().add(asset);
                    }
                } else {
                    Asset asset = new Asset(policyIdAssetName._2, utxoQty);
                    MultiAsset multiAsset = new MultiAsset(policyIdAssetName._1, new ArrayList<>(List.of(asset)));
                    changeOutput.getValue().getMultiAssets().add(multiAsset);
                }
            }
        });

        //Remove any empty MultiAssets
        List<MultiAsset> multiAssets = changeOutput.getValue().getMultiAssets();
        List<MultiAsset> markedForRemoval = new ArrayList<>();
        if (multiAssets != null && multiAssets.size() > 0) {
            multiAssets.forEach(ma -> {
                if (ma.getAssets() == null || ma.getAssets().size() == 0)
                    markedForRemoval.add(ma);
            });

            if (!markedForRemoval.isEmpty()) multiAssets.removeAll(markedForRemoval);
        }
    }

    private List<Utxo> getUtxos(String address, String unit, BigInteger amount, Set<Utxo> excludeUtxos) throws ApiException {
        UtxoSelectionStrategy utxoSelectionStrategy = new DefaultUtxoSelectionStrategyImpl(backendService.getUtxoService());
        return utxoSelectionStrategy.selectUtxos(address, unit, amount, excludeUtxos);
    }

    private MultiAsset createMultiAsset(String policyId) {
        MultiAsset multiAsset = new MultiAsset();
        multiAsset.setPolicyId(policyId);
        multiAsset.getAssets().add(new Asset("test", BigInteger.ONE));
        return multiAsset;
    }

    private BigInteger createDummyOutputAndCalculateMinAdaForTxnOutput(String address, List<MultiAsset> multiAssets, ProtocolParams protocolParams) {
        TransactionOutput txnOutput = new TransactionOutput();
        txnOutput.setAddress(address);
        txnOutput.setValue(new Value(BigInteger.ZERO, multiAssets));
        return (new MinAdaCalculator(protocolParams)).calculateMinAda(txnOutput);
    }
}