package com.winstantpay.dash;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {

       BlockChainService blockChainService = new BlockChainService();
       for(Address address:blockChainService.getWalletApplication().getWallet().getIssuedReceiveAddresses()){
           System.out.println(address);
       }

        Scanner in = new Scanner(System.in);

        int i = in.nextInt();

//        WalletAppKit kit = new WalletAppKit(TestNet3Params.get(), new File("."), "testnet") {
//            @Override
//            protected void onSetupCompleted() {
//                // This is called in a background thread after startAndWait is called, as setting up various objects
//                // can do disk and network IO that may cause UI jank/stuttering in wallet apps if it were to be done
//                // on the main thread.
//                if (wallet().getKeyChainGroupSize() < 1)
//                    wallet().importKey(new ECKey());
////                System.out.println(this.store().getParams().getId());
//            }
//        };
//
//
//
//// Download the block chain and wait until it's done.
//        kit.startAsync();
//        kit.awaitRunning();
    }
}
