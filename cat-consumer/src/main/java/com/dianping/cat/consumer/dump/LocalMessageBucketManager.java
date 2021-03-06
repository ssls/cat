package com.dianping.cat.consumer.dump;

import io.netty.buffer.ByteBuf;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.unidal.helper.Scanners;
import org.unidal.helper.Scanners.FileMatcher;
import org.unidal.helper.Threads;
import org.unidal.helper.Threads.Task;
import org.unidal.lookup.ContainerHolder;
import org.unidal.lookup.annotation.Inject;

import com.dianping.cat.Cat;
import com.dianping.cat.CatConstants;
import com.dianping.cat.configuration.NetworkInterfaceManager;
import com.dianping.cat.configuration.ServerConfigManager;
import com.dianping.cat.hadoop.hdfs.HdfsUploader;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.MessageProducer;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.internal.MessageId;
import com.dianping.cat.message.spi.MessageTree;
import com.dianping.cat.message.spi.core.MessagePathBuilder;
import com.dianping.cat.message.spi.internal.DefaultMessageTree;
import com.dianping.cat.statistic.ServerStatisticManager;
import com.dianping.cat.storage.message.LocalMessageBucket;
import com.dianping.cat.storage.message.MessageBlock;
import com.dianping.cat.storage.message.MessageBucket;
import com.dianping.cat.storage.message.MessageBucketManager;

public class LocalMessageBucketManager extends ContainerHolder implements MessageBucketManager, Initializable,
      LogEnabled {
	public static final String ID = "local";

	private File m_baseDir;

	private ConcurrentHashMap<String, LocalMessageBucket> m_buckets = new ConcurrentHashMap<String, LocalMessageBucket>();

	@Inject
	private ServerConfigManager m_configManager;

	@Inject
	private ServerStatisticManager m_serverStateManager;

	@Inject
	private MessagePathBuilder m_pathBuilder;

	@Inject
	private HdfsUploader m_logviewUploader;

	private String m_localIp = NetworkInterfaceManager.INSTANCE.getLocalHostAddress();

	private Logger m_logger;

	private long m_error;

	private long m_total;

	private int m_gzipThreads = 20;

	private int m_gzipMessageSize = 10000;

	private int m_messageBlockSize = 10000;

	private BlockingQueue<MessageBlock> m_messageBlocks = new LinkedBlockingQueue<MessageBlock>(m_messageBlockSize);

	private ConcurrentHashMap<Integer, LinkedBlockingQueue<MessageItem>> m_messageQueues = new ConcurrentHashMap<Integer, LinkedBlockingQueue<MessageItem>>();

	public void archive(long startTime) {
		String path = m_pathBuilder.getPath(new Date(startTime), "");
		List<String> keys = new ArrayList<String>();

		for (String key : m_buckets.keySet()) {
			if (key.startsWith(path)) {
				keys.add(key);
			}
		}
		for (String key : keys) {
			try {
				LocalMessageBucket bucket = m_buckets.get(key);
				MessageBlock block = bucket.flushBlock();

				if (block != null) {
					m_messageBlocks.put(block);
				}
			} catch (Exception e) {
				Cat.logError(e);
			}
		}
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	public void initialize() throws InitializationException {
		m_baseDir = new File(m_configManager.getHdfsLocalBaseDir(ServerConfigManager.DUMP_DIR));

		Threads.forGroup("cat").start(new BlockDumper(m_buckets, m_messageBlocks, m_serverStateManager));
		Threads.forGroup("cat").start(new LogviewUploader(this, m_buckets, m_logviewUploader, m_configManager));

		if (m_configManager.isLocalMode()) {
			m_gzipThreads = 1;
		}

		for (int i = 0; i < m_gzipThreads; i++) {
			LinkedBlockingQueue<MessageItem> messageQueue = new LinkedBlockingQueue<MessageItem>(m_gzipMessageSize);

			m_messageQueues.put(i, messageQueue);
			Threads.forGroup("cat").start(new MessageGzip(messageQueue, i));
		}
	}

	@Override
	public MessageTree loadMessage(String messageId) {
		MessageProducer cat = Cat.getProducer();
		Transaction t = cat.newTransaction("BucketService", getClass().getSimpleName());

		t.setStatus(Message.SUCCESS);

		try {
			MessageId id = MessageId.parse(messageId);
			final String path = m_pathBuilder.getPath(new Date(id.getTimestamp()), "");
			final File dir = new File(m_baseDir, path);
			final String key = id.getDomain() + '-' + id.getIpAddress();
			final List<String> paths = new ArrayList<String>();

			Scanners.forDir().scan(dir, new FileMatcher() {
				@Override
				public Direction matches(File base, String name) {
					if (name.contains(key) && !name.endsWith(".idx")) {
						paths.add(path + name);
					}
					return Direction.NEXT;
				}
			});

			for (String dataFile : paths) {
				LocalMessageBucket bucket = m_buckets.get(dataFile);

				if (bucket != null) {
					MessageBlock block = bucket.flushBlock();

					if (block != null) {
						boolean first = m_messageBlocks.offer(block);

						LockSupport.parkNanos(200 * 1000 * 1000L); // wait 200 ms

						if (first == false) {
							boolean retry = m_messageBlocks.offer(block);

							if (retry == false) {
								Cat.logError(new RuntimeException("error flush block when read logview"));
							} else {
								LockSupport.parkNanos(200 * 1000 * 1000L); // wait 200 ms
							}
						}
					}
					MessageTree tree = bucket.findById(messageId);

					if (tree != null && tree.getMessageId().equals(messageId)) {
						t.addData("path", dataFile);
						return tree;
					}
				} else {
					File file = new File(m_baseDir, dataFile);

					if (file.exists()) {
						try {
							bucket = (LocalMessageBucket) lookup(MessageBucket.class, LocalMessageBucket.ID);
							bucket.setBaseDir(m_baseDir);
							bucket.initialize(dataFile);

							MessageTree tree = bucket.findById(messageId);

							if (tree != null && tree.getMessageId().equals(messageId)) {
								t.addData("path", dataFile);
								return tree;
							}
						} catch (Exception e) {
							Cat.logError(e);
						} finally {
							bucket.close();
							release(bucket);
						}
					}
				}
			}
			return null;
		} catch (Throwable e) {
			t.setStatus(e);
			cat.logError(e);
		} finally {
			t.complete();
		}
		return null;
	}

	private void logStorageState(final MessageTree tree) {
		int size = ((DefaultMessageTree) tree).getBuffer().readableBytes();
		String domain = tree.getDomain();

		m_serverStateManager.addMessageSize(domain, size);
		if (m_total % (CatConstants.SUCCESS_COUNT) == 0) {
			m_serverStateManager.addMessageDump(CatConstants.SUCCESS_COUNT);

			Message message = tree.getMessage();

			if (message instanceof Transaction) {
				long delay = System.currentTimeMillis() - tree.getMessage().getTimestamp()
				      - ((Transaction) message).getDurationInMillis();

				m_serverStateManager.addProcessDelay(delay);
			}
		}
	}

	public void releaseBucket(LocalMessageBucket bucket) {
		release(bucket);
	}

	public void setBaseDir(File baseDir) {
		m_baseDir = baseDir;
	}

	public void setLocalIp(String localIp) {
		m_localIp = localIp;
	}

	@Override
	public void storeMessage(final MessageTree tree, final MessageId id) {
		m_total++;
		boolean errorFlag = true;
		int index = (int) (m_total % m_gzipThreads);
		MessageItem messageItem = new MessageItem(tree, id);
		int retryTime = 0;

		while (retryTime < m_gzipThreads) {
			LinkedBlockingQueue<MessageItem> queue = m_messageQueues.get((index + retryTime) % m_gzipThreads);
			boolean result = queue.offer(messageItem);

			if (result) {
				errorFlag = false;
				break;
			}
			retryTime++;
		}

		if (errorFlag) {
			m_error++;
			if (m_error % (CatConstants.ERROR_COUNT * 10) == 0) {
				m_logger.error("Error when offer message tree to gzip queue! overflow :" + m_error + ". Gzip thread :"
				      + index);
			}
			m_serverStateManager.addMessageDumpLoss(1);
		}
		logStorageState(tree);
	}

	public class MessageGzip implements Task {

		private int m_index;

		public BlockingQueue<MessageItem> m_messageQueue;

		private int m_count = -1;

		public MessageGzip(BlockingQueue<MessageItem> messageQueue, int index) {
			m_messageQueue = messageQueue;
			m_index = index;
		}

		@Override
		public String getName() {
			return "Message-Gzip-" + m_index;
		}

		private void gzipMessage(MessageItem item) {
			try {
				MessageId id = item.getMessageId();
				String name = id.getDomain() + '-' + id.getIpAddress() + '-' + m_localIp;
				String path = m_pathBuilder.getPath(new Date(id.getTimestamp()), name);
				LocalMessageBucket bucket = m_buckets.get(path);

				if (bucket == null) {
					synchronized (m_buckets) {
						bucket = m_buckets.get(path);
						if (bucket == null) {
							bucket = (LocalMessageBucket) lookup(MessageBucket.class, LocalMessageBucket.ID);
							bucket.setBaseDir(m_baseDir);
							bucket.initialize(path);

							m_buckets.put(path, bucket);
						}
					}
				}

				DefaultMessageTree tree = (DefaultMessageTree) item.getTree();
				ByteBuf buf = tree.getBuffer();
				MessageBlock bolck = bucket.storeMessage(buf, id);

				if (bolck != null) {
					if (!m_messageBlocks.offer(bolck)) {
						m_serverStateManager.addBlockLoss(1);
						Cat.logEvent("DumpError", tree.getDomain());
					}
				}
			} catch (Throwable e) {
				Cat.logError(e);
			}
		}

		private void gzipMessageWithMonitor(MessageItem item) {
			Transaction t = Cat.newTransaction("Gzip", "Thread-" + m_index);
			t.setStatus(Transaction.SUCCESS);

			gzipMessage(item);
			t.complete();
		}

		@Override
		public void run() {
			try {
				while (true) {
					MessageItem item = m_messageQueue.poll(5, TimeUnit.MILLISECONDS);

					if (item != null) {
						m_count++;
						if (m_count % (10000) == 0) {
							gzipMessageWithMonitor(item);
						} else {
							gzipMessage(item);
						}
					}
				}
			} catch (InterruptedException e) {
				// ignore it
			}
		}

		@Override
		public void shutdown() {
		}
	}

}
