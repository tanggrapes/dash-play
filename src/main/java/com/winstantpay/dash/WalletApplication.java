package com.winstantpay.dash;

import com.google.common.base.Stopwatch;
import lombok.Getter;
import lombok.Setter;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Context;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.wallet.*;

import javax.security.auth.login.Configuration;
import java.io.*;
import java.util.concurrent.TimeUnit;

@Getter
@Setter
public class WalletApplication {

    private File walletFile;
    private Wallet wallet;
    private Configuration config;
    public WalletApplication() throws IOException {
        org.bitcoinj.core.Context.enableStrictMode();
        org.bitcoinj.core.Context.propagate(new Context(TestNet3Params.get()));
        initMnemonicCode();
        walletFile = new File("wallet-protobuf-testnet");
        loadWalletFromProtobuf();
        org.bitcoinj.core.Context context = wallet.getContext();
        wallet.getContext().initDash(true, true);

        afterLoadWallet();
        for(Address a : wallet.getIssuedReceiveAddresses()){
            System.out.println(a);
        }
//        cleanupFiles();
    }

//    private void cleanupFiles() {
//        for (final String filename : fileList()) {
//            if (filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_BASE58)
//                    || filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + '.')
//                    || filename.endsWith(".tmp")) {
//                final File file = new File(getFilesDir(), filename);
//                log.info("removing obsolete file: '{}'", file);
//                file.delete();
//            }
//        }
//    }

    private void afterLoadWallet() {
        wallet.autosaveToFile(walletFile, 5000, TimeUnit.MILLISECONDS, null);

        // clean up spam
        try {
            wallet.cleanup();
        }
        catch(IllegalStateException x) {
            //Catch an inconsistent exception here and reset the blockchain.  This is for loading older wallets that had
            //txes with fees that were too low or dust that were stuck and could not be sent.  In a later version
            //the fees were fixed, then those stuck transactions became inconsistant and the exception is thrown.
//            if(x.getMessage().contains("Inconsistent spent tx:"))
//            {
//                File blockChainFile = new File(getDir("blockstore", 0x0000), "blockchain-testnet");
//                blockChainFile.delete();
//            }
//            else throw x;
        }

        // make sure there is at least one recent backup
        if (!new File("key-backup-protobuf-testnet").exists())
            backupWallet();
    }



    private void initMnemonicCode() {
        try {
            final Stopwatch watch = Stopwatch.createStarted();
            MnemonicCode.INSTANCE = new MnemonicCode(this.getClass().getClassLoader().getResourceAsStream("bip39-wordlist.txt"), null);
            watch.stop();
//            log.info("BIP39 wordlist loaded from: '{}', took {}", BIP39_WORDLIST_FILENAME, watch);
        } catch (final IOException x) {
            throw new Error(x);
        }
    }

    private void loadWalletFromProtobuf() {
        if (walletFile.exists()) {
            System.out.println("exist");
            FileInputStream walletStream = null;

            try {
                final Stopwatch watch = Stopwatch.createStarted();
                walletStream = new FileInputStream(walletFile);
                wallet = new WalletProtobufSerializer().readWallet(walletStream);

                if (!wallet.getParams().equals(TestNet3Params.get()))
                    throw new UnreadableWalletException("bad wallet network parameters: " + wallet.getParams().getId());

//                log.info("wallet loaded from: '{}', took {}", walletFile, watch);
            } catch (final FileNotFoundException x) {
//                log.error("problem loading wallet", x);

//                Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

                wallet = restoreWalletFromBackup();
            } catch (final UnreadableWalletException x) {
//                log.error("problem loading wallet", x);

//                Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

                wallet = restoreWalletFromBackup();
            } finally {
                if (walletStream != null) {
                    try {
                        walletStream.close();
                    } catch (final IOException x) {
                        // swallow
                    }
                }
            }

            if (!wallet.isConsistent()) {

                wallet = restoreWalletFromBackup();
            }

            if (!wallet.getParams().equals(TestNet3Params.get()))
                throw new Error("bad wallet network parameters: " + wallet.getParams().getId());
        } else {
            wallet = new Wallet(TestNet3Params.get());
            wallet.addKeyChain(DeterministicKeyChain.BIP44_ACCOUNT_ZERO_PATH_TESTNET);

            saveWallet();
            backupWallet();

//            config.armBackupReminder();

//            log.info("new wallet created");
        }
    }

    public void backupWallet() {
        final Stopwatch watch = Stopwatch.createStarted();
        final Protos.Wallet.Builder builder = new WalletProtobufSerializer().walletToProto(wallet).toBuilder();

        // strip redundant
        builder.clearTransaction();
        builder.clearLastSeenBlockHash();
        builder.setLastSeenBlockHeight(-1);
        builder.clearLastSeenBlockTimeSecs();
        final Protos.Wallet walletProto = builder.build();

        OutputStream os = null;

        try {
//            os = openFileOutput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, Context.MODE_PRIVATE);
            os = new FileOutputStream("key-backup-protobuf-testnet");
            walletProto.writeTo(os);
            watch.stop();
//            log.info("wallet backed up to: '{}', took {}", Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, watch);
        } catch (final IOException x) {
//            log.error("problem writing wallet backup", x);
        } finally {
            try {
                os.close();
            } catch (final IOException x) {
                // swallow
            }
        }
    }

    public void saveWallet() {
        try {
            protobufSerializeWallet(wallet);
        } catch (final IOException x) {
            throw new RuntimeException(x);
        }
    }

    private void protobufSerializeWallet(final Wallet wallet) throws IOException {
        final Stopwatch watch = Stopwatch.createStarted();
        wallet.saveToFile(walletFile);
        watch.stop();

//        log.info("wallet saved to: '{}', took {}", walletFile, watch);
    }

    private Wallet restoreWalletFromBackup() {
        InputStream is = null;

        try {
//            is = openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF);
            is = new FileInputStream(new File("key-backup-protobuf-testnet"));

            final Wallet wallet = new WalletProtobufSerializer().readWallet(is, true, null);

            if (!wallet.isConsistent())
                throw new Error("inconsistent backup");

            wallet.addKeyChain(DeterministicKeyChain.BIP44_ACCOUNT_ZERO_PATH_TESTNET);

//            resetBlockchain();

//            Toast.makeText(this, R.string.toast_wallet_reset, Toast.LENGTH_LONG).show();

//            log.info("wallet restored from backup: '" + Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + "'");

            return wallet;
        } catch (final IOException x) {
            throw new Error("cannot read backup", x);
        } catch (final UnreadableWalletException x) {
            throw new Error("cannot read backup", x);
        } finally {
            try {
                is.close();
            } catch (final IOException x) {
                // swallow
            }
        }
    }


}
