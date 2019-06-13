package com.winstantpay.dash;

import com.google.common.base.Stopwatch;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.*;
import org.bitcoinj.core.listeners.AbstractPeerDataEventListener;
import org.bitcoinj.core.listeners.PeerConnectedEventListener;
import org.bitcoinj.core.listeners.PeerDataEventListener;
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener;
import org.bitcoinj.evolution.SimplifiedMasternodeList;
import org.bitcoinj.net.discovery.*;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;

import javax.security.auth.login.Configuration;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class BlockChainService {
    @Getter
    private WalletApplication walletApplication;
    private BlockStore blockStore;
    private File blockChainFile;
    private BlockChain blockChain;
    private PeerGroup peerGroup;
    private PeerConnectivityListener peerConnectivityListener;
    private Configuration config;
    private final SeedPeers seedPeerDiscovery = new SeedPeers(TestNet3Params.get());
    private final DnsDiscovery dnsDiscovery = new DnsDiscovery(new String[]{"95.183.51.146", "35.161.101.35", "54.91.130.170"}, TestNet3Params.get());
    ArrayList<PeerDiscovery> peerDiscoveryList = new ArrayList<PeerDiscovery>(2);
    public BlockChainService(){
        try {
            walletApplication = new WalletApplication();
        } catch (IOException e) {
            e.printStackTrace();
        }

        final Wallet wallet = walletApplication.getWallet();
        peerConnectivityListener = new PeerConnectivityListener();
        System.out.println(wallet.getBalance());
        System.out.println(wallet.currentReceiveAddress());

        blockChainFile = new File("blockstore", "blockchain-testnet");
        final boolean blockChainFileExists = blockChainFile.exists();

        if (!blockChainFileExists) {
            log.info("blockchain does not exist, resetting wallet");
            wallet.reset();
        }

        try {
            blockStore = new SPVBlockStore(TestNet3Params.get(), blockChainFile);
            blockStore.getChainHead(); // detect corruptions as early as possible

            final long earliestKeyCreationTime = wallet.getEarliestKeyCreationTime();

            if (!blockChainFileExists && earliestKeyCreationTime > 0) {
                try {
                    final Stopwatch watch = Stopwatch.createStarted();
                    final InputStream checkpointsInputStream = this.getClass().getClassLoader().getResourceAsStream("checkpoints.txt");
                    CheckpointManager.checkpoint(TestNet3Params.get(), checkpointsInputStream, blockStore,
                            earliestKeyCreationTime);
                    watch.stop();
                    log.info("checkpoints loaded from '{}', took {}", "checkpoints.txt", watch);
                } catch (final IOException x) {
                    log.error("problem reading checkpoints, continuing without", x);
                }
            }
        } catch (final BlockStoreException x) {
            blockChainFile.delete();

            final String msg = "blockstore cannot be created";
            log.error(msg, x);
            throw new Error(msg, x);
        }

        try {
            blockChain = new BlockChain(TestNet3Params.get(), wallet, blockStore);
        } catch (final BlockStoreException x) {
            throw new Error("blockchain cannot be created", x);
        }


        wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletEventListener);
        wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletEventListener);
        wallet.addChangeEventListener(Threading.SAME_THREAD, walletEventListener);
//
//        walletApplication.getWallet().getContext().sporkManager.addEventListener(sporkUpdatedEventListener, Threading.SAME_THREAD);

//        registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));

        wallet.getContext().initDashSync("masternode");
//

        peerDiscoveryList.add(dnsDiscovery);


        peerGroup = new PeerGroup(TestNet3Params.get(), blockChain);
        peerGroup.setDownloadTxDependencies(0); // recursive implementation causes StackOverflowError
        peerGroup.addWallet(wallet);
        peerGroup.setUserAgent("Dash Wallet", "1.0.0");
        peerGroup.addConnectedEventListener(peerConnectivityListener);
        peerGroup.addDisconnectedEventListener(peerConnectivityListener);

        final int maxConnectedPeers = 4;

        final String trustedPeerHost = null;
        final boolean hasTrustedPeer = trustedPeerHost != null;

        final boolean connectTrustedPeerOnly = hasTrustedPeer && false;
        peerGroup.setMaxConnections(4);
        peerGroup.setConnectTimeoutMillis(15000);
        peerGroup.setPeerDiscoveryTimeoutMillis(10000);

        peerGroup.addPeerDiscovery(new PeerDiscovery() {
            //Keep Original code here for now
            //private final PeerDiscovery normalPeerDiscovery = MultiplexingDiscovery
            //        .forServices(Constants.NETWORK_PARAMETERS, 0);
            private final PeerDiscovery normalPeerDiscovery = new MultiplexingDiscovery(TestNet3Params.get(), peerDiscoveryList);


            @Override
            public InetSocketAddress[] getPeers(final long services, final long timeoutValue,
                                                final TimeUnit timeoutUnit) throws PeerDiscoveryException {
                final List<InetSocketAddress> peers = new LinkedList<InetSocketAddress>();

                boolean needsTrimPeersWorkaround = false;

                if (hasTrustedPeer) {
                    log.info(
                            "trusted peer '" + trustedPeerHost + "'" + (connectTrustedPeerOnly ? " only" : ""));

                    final InetSocketAddress addr = new InetSocketAddress(trustedPeerHost,
                            TestNet3Params.get().getPort());
                    if (addr.getAddress() != null) {
                        peers.add(addr);
                        needsTrimPeersWorkaround = true;
                    }
                }

                if (!connectTrustedPeerOnly) {
                    try {
                        peers.addAll(
                                Arrays.asList(normalPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)));
                    } catch (PeerDiscoveryException x) {
                        //swallow and continue with another method of connection.
                        log.info("DNS peer discovery failed: "+ x.getMessage());
                        if(x.getCause() != null)
                            log.info(  "cause:  " + x.getCause().getMessage());
                    }
                    if(peers.size() < 10) {
                        log.info("DNS peer discovery returned less than 10 nodes.  Adding DMN peers to the list to increase connections");
                        try {
                            SimplifiedMasternodeList mnlist = org.bitcoinj.core.Context.get().masternodeListManager.getListAtChainTip();
                            MasternodePeerDiscovery discovery = new MasternodePeerDiscovery(mnlist);
                            peers.addAll(Arrays.asList(discovery.getPeers(services, timeoutValue, timeoutUnit)));
                        } catch (PeerDiscoveryException x) {
                            //swallow and continue with another method of connection
                            log.info("DMN List peer discovery failed: "+ x.getMessage());

                        }

                        if(peers.size() < 10) {
                            if (TestNet3Params.get().getAddrSeeds() != null) {
                                log.info("DNS peer discovery returned less than 10 nodes.  Adding seed peers to the list to increase connections");
                                peers.addAll(Arrays.asList(seedPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)));
                            } else {
                                log.info("DNS peer discovery returned less than 10 nodes.  Unable to add seed peers (it is not specified for this network).");
                            }
                        }
                    }
                }

                // workaround because PeerGroup will shuffle peers
                if (needsTrimPeersWorkaround)
                    while (peers.size() >= maxConnectedPeers)
                        peers.remove(peers.size() - 1);

                return peers.toArray(new InetSocketAddress[0]);
            }

            @Override
            public void shutdown() {
                normalPeerDiscovery.shutdown();
            }
        });

        peerGroup.startAsync();
        peerGroup.startBlockChainDownload(blockchainDownloadListener);
    }

    private final PeerDataEventListener blockchainDownloadListener = new AbstractPeerDataEventListener() {
        private final AtomicLong lastMessageTime = new AtomicLong(0);

        @Override
        public void onBlocksDownloaded(final Peer peer, final Block block, final FilteredBlock filteredBlock,
                                       final int blocksLeft) {
            System.out.println("new block");
        }


    };
    private final class PeerConnectivityListener
            implements PeerConnectedEventListener, PeerDisconnectedEventListener {
        private int peerCount;
        private AtomicBoolean stopped = new AtomicBoolean(false);

        public PeerConnectivityListener() {

        }

        public void stop() {
            System.out.println("stop");
        }

        @Override
        public void onPeerConnected(final Peer peer, final int peerCount) {
            this.peerCount = peerCount;
            changed(peerCount);
        }

        @Override
        public void onPeerDisconnected(final Peer peer, final int peerCount) {
            this.peerCount = peerCount;
            changed(peerCount);
        }


        private void changed(final int numPeers) {
            if (stopped.get())
                return;


        }
    }


    private final ThrottlingWalletChangeListener walletEventListener = new ThrottlingWalletChangeListener(){

        @Override
        public void onThrottledWalletChanged() {
            System.out.println("onThrottledWalletChanged");
        }

        @Override
        public void onCoinsReceived(final Wallet wallet, final Transaction tx, final Coin prevBalance,
                                    final Coin newBalance) {

            System.out.println("prev - " + prevBalance.value);
            System.out.println("newBalance - " + newBalance.value);

//            final int bestChainHeight = blockChain.getBestChainHeight();
//            final boolean replaying = bestChainHeight < config.getBestChainHeightEver();
//
//            long now = new Date().getTime();
//            long blockChainHeadTime = blockChain.getChainHead().getHeader().getTime().getTime();
//            boolean insideTxExchangeRateTimeThreshold = (now - blockChainHeadTime) < TX_EXCHANGE_RATE_TIME_THRESHOLD_MS;
//
//            if (tx.getExchangeRate() == null && ((!replaying || insideTxExchangeRateTimeThreshold) || tx.getConfidence().getConfidenceType() == TransactionConfidence.ConfidenceType.PENDING)) {
//                try {
//                    final de.schildbach.wallet.rates.ExchangeRate exchangeRate = AppDatabase.getAppDatabase()
//                            .exchangeRatesDao().getRateSync(config.getExchangeCurrencyCode());
//                    if (exchangeRate != null) {
//                        log.info("Setting exchange rate on received transaction.  Rate:  " + exchangeRate.toString() + " tx: " + tx.getHashAsString());
//                        tx.setExchangeRate(new ExchangeRate(Coin.COIN, exchangeRate.getFiat()));
//                        application.saveWallet();
//                    }
//                } catch (Exception e) {
//                    log.error("Failed to get exchange rate", e);
//                }
//            }
//
//            transactionsReceived.incrementAndGet();
//
//
//            final Address address = WalletUtils.getWalletAddressOfReceived(tx, wallet);
//            final Coin amount = tx.getValue(wallet);
//            final TransactionConfidence.ConfidenceType confidenceType = tx.getConfidence().getConfidenceType();
//
//            handler.post(new Runnable() {
//                @Override
//                public void run() {
//                    final boolean isReceived = amount.signum() > 0;
//                    final boolean isReplayedTx = confidenceType == ConfidenceType.BUILDING && replaying;
//
//                    if (isReceived && !isReplayedTx)
//                        notifyCoinsReceived(address, amount, tx.getExchangeRate());
//                }
//            });
        }

        @Override
        public void onCoinsSent(final Wallet wallet, final Transaction tx, final Coin prevBalance,
                                final Coin newBalance) {

        }
    };
}
