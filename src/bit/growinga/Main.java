package bit.growinga;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.BloomFilter;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.InventoryItem;
import org.bitcoinj.core.InventoryMessage;
import org.bitcoinj.core.MemoryPoolMessage;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBroadcast;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.AbstractBlockChain.NewBlockType;
import org.bitcoinj.core.listeners.PreMessageReceivedEventListener;
import org.bitcoinj.core.listeners.TransactionReceivedInBlockListener;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import com.google.common.util.concurrent.ListenableFuture;

public class Main {
	public static void main(String[] args) throws BlockStoreException,
			InterruptedException, ExecutionException, NoSuchAlgorithmException,
			IOException {
		final NetworkParameters params = TestNet3Params.get();

		BlockStore blockStore = new MemoryBlockStore(params);
		BlockChain chain = new BlockChain(params, blockStore);

		WalletAppKit walletkit = new WalletAppKit(params, new File("."),
				"sendrequest-example");

		System.out.println("start async");
		walletkit.startAsync();
		System.out.println("await running");
		walletkit.awaitRunning();

		Wallet wallet = walletkit.wallet();
		System.out.println("balance: " + wallet.getBalance().getValue());
		System.out.println("Send money to: "
				+ wallet.currentReceiveAddress().toString());

		String ipfs_urn = "urn:pok:ipfs:QmPLPUjGF4T9QzhxLZpSmeK6S3G6aW1gN1jWtAC3P8vUXh";

		// Construct a OP_RETURN transaction
		Transaction transaction = new Transaction(walletkit.params());
		transaction.addOutput(Coin.ZERO,
				ScriptBuilder.createOpReturnScript(ipfs_urn
						.getBytes(StandardCharsets.UTF_8)));

		SendRequest send_request = SendRequest.forTx(transaction);

		try {
			wallet.completeTx(send_request);
		} catch (InsufficientMoneyException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.err.println("NON HAI ABBASTANZA SOLDINI");
			return;
		}

		// Return a reference to the caller
		System.out.println("tx_hash " + transaction.getHashAsString());
		System.out.println("fee " + transaction.getFee().getValue());
		System.out.println("tx " + transaction.toString());

		System.out.println("doing broadcast");
		TransactionBroadcast broadcast = walletkit.peerGroup()
				.broadcastTransaction(transaction);
		Transaction t = broadcast.future().get();
		System.out.println("done broadcast");

		double falsePositiveRate = 0d;
		/**
		 * Ricerco la transazione a parte dal bloomfilter
		 */
		BloomFilter bloom_filter = wallet.getBloomFilter(falsePositiveRate);
		PeerGroup peer_group = walletkit.peerGroup();
		// peer_group.start();
		// peer_group.addAddress(new PeerAddress(params,
		// InetAddress.getLocalHost()));
		peer_group.waitForPeers(1).get();
		Peer peer = peer_group.getConnectedPeers().get(0);
		System.out.println("doing ping");
		peer.ping().get();
		System.out.println("PONG");
		boolean useFilteredBlocks = true;
		long secondsSinceEpoch = ((new Date()).getTime() / 1000)
				- (6 * 60 * 60);
		System.out.println("secondsSinceEpoch " + secondsSinceEpoch);
		peer.setDownloadParameters(secondsSinceEpoch, useFilteredBlocks);

		final List<Sha256Hash> received_txs = new ArrayList<Sha256Hash>();
		PreMessageReceivedEventListener pre = new PreMessageReceivedEventListener() {

			@Override
			public Message onPreMessageReceived(Peer peer, Message m) {
				// TODO Auto-generated method stub
				if (m instanceof MemoryPoolMessage) {
					System.out.println("E' arrivato un MemoryPoolMessage!!");
					MemoryPoolMessage mpm = (MemoryPoolMessage) m;
				}

				if (m instanceof InventoryMessage) {
					System.out.println("E' arrivato un InventoryMessage!!");
					InventoryMessage im = (InventoryMessage) m;
					Iterator<InventoryItem> it = im.getItems().iterator();
					while (it.hasNext()) {
						InventoryItem inventoryItem = (InventoryItem) it.next();
						System.out.println("inventoryItem "
								+ inventoryItem.type);
						if (inventoryItem.type == InventoryItem.Type.Transaction) {
							Sha256Hash tx_hash = inventoryItem.hash;
							received_txs.add(tx_hash);
						}
					}
				}
				return m;
			}
		};
		peer.addPreMessageReceivedEventListener(Threading.SAME_THREAD, pre);

		TransactionReceivedInBlockListener tx_listener = new TransactionReceivedInBlockListener() {

			@Override
			public void receiveFromBlock(Transaction tx, StoredBlock block,
					NewBlockType blockType, int relativityOffset)
					throws VerificationException {
				// TODO Auto-generated method stub
				System.out.println("receiveFromBlock " + tx.toString());
			}

			@Override
			public boolean notifyTransactionIsInBlock(Sha256Hash txHash,
					StoredBlock block, NewBlockType blockType,
					int relativityOffset) throws VerificationException {
				// TODO Auto-generated method stub
				return false;
			}
		};
		// chain.addTransactionReceivedListener(tx_listener);

		peer.setBloomFilter(bloom_filter);

		System.out.println("return per controllare le tx");
		System.in.read();

		Iterator<Sha256Hash> it_txs = received_txs.iterator();
		while (it_txs.hasNext()) {
			Sha256Hash txHash = (Sha256Hash) it_txs.next();
			ListenableFuture<Transaction> future = peer
					.getPeerMempoolTransaction(txHash);
			System.out
					.println("Waiting for node to send us the requested transaction: "
							+ txHash);
			Transaction tx = future.get();
			System.out.println(tx);
			List<TransactionOutput> outputs = tx.getOutputs();
			for (TransactionOutput to : outputs) {
				if (to.getScriptPubKey().isOpReturn()) {
					System.out.println("Trovata un OP_RETURN!!");
					byte[] clean_script_bytes = Arrays.copyOfRange(
							to.getScriptBytes(), 2, to.getScriptBytes().length);
					String op_return = new String(clean_script_bytes,
							StandardCharsets.UTF_8);
					System.out.println("OP_RETURN " + op_return);
				}
			}
		}
	}
}
