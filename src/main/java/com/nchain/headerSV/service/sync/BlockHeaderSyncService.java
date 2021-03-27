package com.nchain.headerSV.service.sync;


import com.nchain.headerSV.config.NetworkConfiguration;
import com.nchain.headerSV.service.HeaderSvService;
import com.nchain.headerSV.service.consumer.MessageConsumer;
import com.nchain.headerSV.service.network.NetworkService;
import com.nchain.jcl.net.network.PeerAddress;
import com.nchain.jcl.net.protocol.messages.*;
import com.nchain.jcl.net.protocol.messages.common.BitcoinMsg;
import com.nchain.jcl.store.blockChainStore.BlockChainStore;
import io.bitcoinj.bitcoin.api.base.HeaderReadOnly;
import io.bitcoinj.bitcoin.bean.base.HeaderBean;
import io.bitcoinj.bitcoin.bean.extended.LiteBlockBean;
import io.bitcoinj.core.Sha256Hash;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;


import java.util.*;
import java.util.stream.Collectors;


/**
 * @author {m.fletcher}@nchain.com
 * Copyright (c) 2018-2020 nChain Ltd
 * @date 27/07/2020
 */
@Service
@Slf4j
@ConfigurationProperties(prefix = "headersv.sync")
public class BlockHeaderSyncService implements HeaderSvService, MessageConsumer {

    private final NetworkService networkService;
    private final NetworkConfiguration networkConfiguration;
    private final BlockChainStore blockStore;

    @Setter
    private Set<String> headersToIgnore = Collections.emptySet();

    protected BlockHeaderSyncService(NetworkService networkService,
                                     NetworkConfiguration networkConfiguration,
                                     BlockChainStore blockStore) {
        this.networkService = networkService;
        this.networkConfiguration = networkConfiguration;
        this.blockStore = blockStore;
    }

    @Override
    public void start() {
        log.info("Starting blockstore..");
        blockStore.start();
        log.info("Blockstore started");

        log.info("Current blockchain state: ");
        blockStore.getTipsChains().forEach(t -> {
            log.info("Chain Id: " + blockStore.getBlockChainInfo(t).get().getHeader().getHash() + " Height: " + blockStore.getBlockChainInfo(t).get().getHeight());
        });


        log.info("Listening for headers...");

        networkService.subscribe(HeadersMsg.class, this::consume, true, false);
        networkService.subscribe(VersionAckMsg.class, this::consume, false, true);
    }

    @Override
    public void stop() {}

    @Override
    public void consume(BitcoinMsg message, PeerAddress peerAddress) {
        log.debug("Consuming message type: " + message.getHeader().getCommand());

        switch(message.getHeader().getCommand()){
            case HeadersMsg.MESSAGE_TYPE:
                consumeHeadersMsg((HeadersMsg) message.getBody(), peerAddress);
                break;

            case VersionAckMsg.MESSAGE_TYPE:
                consumeVersionAckMsg((VersionAckMsg) message.getBody(), peerAddress);
                break;

            default:
                throw new UnsupportedOperationException("Unhandled Message Type: " + message.getBody().getMessageType());
        }
    }

    private void consumeVersionAckMsg(VersionAckMsg versionAckMsg, PeerAddress peerAddress) {
        // request headers for each tip, at this point we don't know which nodes are SV and which are not
        blockStore.getTipsChains().forEach(h -> {
            //Let this peer know where we've sync'd up too
            updatePeerWithLatestHeader(h, peerAddress);

            // Ask peer to keep up this node updated of latest headers
            requestPeerToSendNewHeaders(h, peerAddress);

            //Request any headers the peer has from our latest tip
            requestHeadersFromHash(h, peerAddress);
        });

    }

    /* Although blockStore is synchronized, we need to ensure another thread does not access
       simultaneously else we risk requesting multiple headers for already processed blocks. */
    private synchronized void consumeHeadersMsg(HeadersMsg headerMsg, PeerAddress peerAddress){

        //Convert each BlockHeaderMsg to a BlockHeader
        List<HeaderReadOnly> blockHeaders = new ArrayList<>(headerMsg.getBlockHeaderMsgList().size());
        for(BlockHeaderMsg blockHeaderMsg : headerMsg.getBlockHeaderMsgList()){

            //convert the header message to a header
            HeaderReadOnly header = BlockHeaderMsgToBean(blockHeaderMsg);

            //Reject the whole message if the peer is sending bad blocks
            if (!validBlockHeader(header, peerAddress)){
                return;
            }

            //add to list to be processed
            blockHeaders.add(header);
        }

        //We only want to request headers for tips that have changed
        List<Sha256Hash> branchTips = blockStore.getTipsChains();

        blockStore.saveBlocks(blockHeaders);

        //check which tips have changed
        List<Sha256Hash> updatedtips = blockStore.getTipsChains().stream().filter(h -> !branchTips.contains(h)).collect(Collectors.toList());

        //Update the peers with the latest tips
        updatedtips.stream().forEach(this::updatePeersWithLatestHeader);

        //For the tips that have changed,
        updatedtips.stream().forEach(this::requestHeadersFromHash);

    }

    private HeaderReadOnly BlockHeaderMsgToBean(BlockHeaderMsg headersMsg){
        HeaderBean headerBean = new HeaderBean(new LiteBlockBean());
        headerBean.setTime(headersMsg.getCreationTimestamp());
        headerBean.setDifficultyTarget(headersMsg.getDifficultyTarget());
        headerBean.setNonce(headersMsg.getNonce());
        headerBean.setPrevBlockHash(Sha256Hash.wrapReversed(headersMsg.getPrevBlockHash().getHashBytes()));
        headerBean.setVersion(headersMsg.getVersion());
        headerBean.setMerkleRoot(Sha256Hash.wrapReversed(headersMsg.getMerkleRoot().getHashBytes()));
        headerBean.setHash(Sha256Hash.wrapReversed(headersMsg.getHash().getHashBytes()));

        return headerBean;
    }

    private boolean validBlockHeader(HeaderReadOnly header, PeerAddress peerAddress){
        //Reject the whole message if any of them are in the ignore list
        if(headersToIgnore.contains(header.getHash().toString())){
            log.info("Message containing header: " + header.getHash().toString() + " has been rejected due to being in the ignore list");
            networkService.blacklistPeer(peerAddress);
            return false;
        }

        /* We don't want to process this message, even it has some headers. Oherwise the different threads may request a branch that has already been processed, slowing down sync times.
           This also catches duplicate messages, so there's no need to store the message checksum and compare each message*/
        if (blockStore.getTipsChains().contains(header.getHash())) {
            log.debug("Message containing header: " + header.getHash().toString() + " has been rejected due to it containing processed headers");
            return false;
        }

        return true;
    }

    private void requestHeadersFromHash(Sha256Hash hash){
        log.info("Requesting headers from block: " + hash + " at height: " + blockStore.getBlockChainInfo(hash).get().getHeight());
        networkService.broadcast(buildGetHeaderMsgFromHash(hash), true);
    }

    private void requestHeadersFromHash(Sha256Hash hash, PeerAddress peerAddress){
       log.info("Requesting headers from block: " + hash + " at height: " + blockStore.getBlockChainInfo(hash).get().getHeight() + " from peer: " + peerAddress);
        networkService.send(buildGetHeaderMsgFromHash(hash), peerAddress, false);
    }

    private void requestPeerToSendNewHeaders(Sha256Hash hash, PeerAddress peerAddress){
        log.info("Requesting peer: " + peerAddress + " to inform client of any new headers");
        networkService.send(buildSendHeadersMsg(), peerAddress, false);
    }

    private void updatePeerWithLatestHeader(Sha256Hash hash, PeerAddress peerAddress){
        log.info("Advertising to peer: " + peerAddress + " that chain tip is: " + hash);
        networkService.send(buildBlockInventoryMsg(hash), peerAddress, false);
    }

    private void updatePeersWithLatestHeader(Sha256Hash hash){
        log.info("Advertising to all peers that chain tip is: " + hash);
        networkService.broadcast(buildBlockInventoryMsg(hash), false);
    }

    private SendHeadersMsg buildSendHeadersMsg(){
        SendHeadersMsg sendHeadersMsg = SendHeadersMsg.builder().build();

        return sendHeadersMsg;
    }

    private InvMessage buildBlockInventoryMsg(Sha256Hash hash) {
        HashMsg hashMsg = HashMsg.builder().hash(hash.getReversedBytes()).build();

        InventoryVectorMsg inventoryVectorMsg = InventoryVectorMsg.builder().type(InventoryVectorMsg.VectorType.MSG_BLOCK).hashMsg(hashMsg).build();
        InvMessage invMessage = InvMessage.builder().invVectorMsgList(Arrays.asList(inventoryVectorMsg)).build();

        return invMessage;
    }

    private GetHeadersMsg buildGetHeaderMsgFromHash(Sha256Hash hash){
        HashMsg hashMsg = HashMsg.builder().hash(hash.getReversedBytes()).build();
        List<HashMsg> hashMsgs = Arrays.asList(hashMsg);

        BaseGetDataAndHeaderMsg baseGetDataAndHeaderMsg = BaseGetDataAndHeaderMsg.builder()
                .version(networkConfiguration.getProtocolConfig().getBasicConfig().getProtocolVersion())
                .blockLocatorHash(hashMsgs)
                .hashCount(VarIntMsg.builder().value(1).build())
                .hashStop(HashMsg.builder().hash(Sha256Hash.ZERO_HASH.getBytes()).build())
                .build();

        GetHeadersMsg getHeadersMsg = GetHeadersMsg.builder()
                .baseGetDataAndHeaderMsg(baseGetDataAndHeaderMsg)
                .build();

        return getHeadersMsg;
    }

}
