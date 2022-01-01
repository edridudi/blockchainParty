package games.blockchainparty.controllers;

import com.bloxbean.cardano.client.backend.exception.ApiException;
import com.bloxbean.cardano.client.backend.exception.InsufficientBalanceException;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import games.blockchainparty.controllers.model.SubmitRequest;
import games.blockchainparty.services.FilesService;
import games.blockchainparty.services.MinterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
public class BaseController {

    private final MinterService minterService;
    private final FilesService filesService;

    @Autowired
    public BaseController(MinterService minterService, FilesService filesService) {
        this.minterService = minterService;
        this.filesService = filesService;
    }

    @GetMapping("/alreadyminted/{number}")
    @ResponseBody
    public ResponseEntity<Boolean> alreadyMinted(@PathVariable Integer number) {
        if (filesService.exists("tx_" + number)) {
            return ResponseEntity.ok(true);
        } else {
            return ResponseEntity.ok(false);
        }
    }

    @GetMapping("/mintingtx/{userAddr}/{number}")
    @ResponseBody
    public ResponseEntity<String> mintingTx(@PathVariable String userAddr, @PathVariable Integer number) {
        if (filesService.exists("tx_" + number)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            return ResponseEntity.ok(minterService.createTransaction(userAddr, number));
        } catch (ApiException | InsufficientBalanceException | CborSerializationException e) {
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/submit")
    @ResponseBody
    public ResponseEntity<Boolean> mintingTx(@RequestBody SubmitRequest submitRequest) {
        if (filesService.exists("tx_" + submitRequest.getNumber())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            String txId = minterService.submitTransaction(submitRequest);
            if (txId == null || txId.isEmpty()) {
                return ResponseEntity.ok(false);
            } else {
                filesService.writeToFile("tx_" + submitRequest.getNumber(), txId);
                return ResponseEntity.ok(true);
            }
        } catch (CborDeserializationException | CborSerializationException | ApiException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}