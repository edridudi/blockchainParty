package com.bloxbean.cardano.client.backend.api.helper.impl;

import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.api.helper.UtxoSelectionStrategy;
import com.bloxbean.cardano.client.backend.common.OrderEnum;
import com.bloxbean.cardano.client.backend.exception.ApiException;
import com.bloxbean.cardano.client.backend.model.Amount;
import com.bloxbean.cardano.client.backend.model.Result;
import com.bloxbean.cardano.client.backend.model.Utxo;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.bloxbean.cardano.client.common.CardanoConstants.LOVELACE;

/**
 * Out-of-box implementation of {@link UtxoSelectionStrategy}
 * Applications can provide their own custom implementation
 */
public class OnlyAdaUtxoSelectionStrategyImpl implements UtxoSelectionStrategy {

    private UtxoService utxoService;

    private boolean ignoreUtxosWithDatumHash;

    public OnlyAdaUtxoSelectionStrategyImpl(UtxoService utxoService) {
        this.utxoService = utxoService;
        this.ignoreUtxosWithDatumHash = true;
    }

    @Override
    public List<Utxo> selectUtxos(String address, String unit, BigInteger amount, Set<Utxo> excludeUtxos) throws ApiException {
        if (amount == null)
            amount = BigInteger.ZERO;

        BigInteger totalUtxoAmount = BigInteger.valueOf(0);
        List<Utxo> selectedUtxos = new ArrayList<>();
        boolean canContinue = true;
        int i = 1;

        while (canContinue) {
            Result<List<Utxo>> result = utxoService.getUtxos(address, getUtxoFetchSize(),
                    i++, getUtxoFetchOrder());
            if (result.code() == 200) {
                List<Utxo> fetchData = result.getValue();

                List<Utxo> data = filter(fetchData);
                if (data == null || data.isEmpty())
                    canContinue = false;

                for (Utxo utxo : data) {
                    if (excludeUtxos.contains(utxo))
                        continue;

                    if (containsTokens(utxo)) {
                        continue;
                    }

                    if (utxo.getDataHash() != null && !utxo.getDataHash().isEmpty() && ignoreUtxosWithDatumHash())
                        continue;

                    List<Amount> utxoAmounts = utxo.getAmount();

                    boolean unitFound = false;
                    for (Amount amt : utxoAmounts) {
                        if (unit.equals(amt.getUnit())) {
                            totalUtxoAmount = totalUtxoAmount.add(amt.getQuantity());
                            unitFound = true;
                        }
                    }

                    if (unitFound)
                        selectedUtxos.add(utxo);

                    if (totalUtxoAmount.compareTo(amount) == 1) {
                        canContinue = false;
                        break;
                    }
                }
            } else {
                canContinue = false;
                throw new ApiException(String.format("Unable to get enough Utxos for address : %s, reason: %s", address, result.getResponse()));
            }
        }

        return selectedUtxos;
    }

    @Override
    public List<Utxo> selectUtxos(String s, String s1, BigInteger bigInteger, String s2, Set<Utxo> set) throws ApiException {
        return null;
    }

    private boolean containsTokens(Utxo utxo) {
        for (Amount amount : utxo.getAmount()) {
            if (!amount.getUnit().equals(LOVELACE)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean ignoreUtxosWithDatumHash() {
        return ignoreUtxosWithDatumHash;
    }

    @Override
    public void setIgnoreUtxosWithDatumHash(boolean ignoreUtxosWithDatumHash) {
        this.ignoreUtxosWithDatumHash = ignoreUtxosWithDatumHash;
    }

    protected List<Utxo> filter(List<Utxo> fetchData) {
        return fetchData;
    }

    protected OrderEnum getUtxoFetchOrder() {
        return OrderEnum.asc;
    }

    protected int getUtxoFetchSize() {
        return 40;
    }
}