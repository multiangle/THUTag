package org.thunlp.crawler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.params.HttpConnectionParams;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.thunlp.crawler.InternalCrawlerListener.InternalResult;

/**
 * Generic web resource crawler
 * 
 * @author adam
 *
 */
public class WebCrawler {
	static private Logger LOG = Logger.getLogger(WebCrawler.class.getName());
	public static String USER_AGENT_FIREFOX = "Mozilla/5.0 (Windows; U; Windows NT 6.1; pl; rv:1.9.1) Gecko/20090624 Firefox/3.5 (.NET CLR 3.5.30729)";
	public static String USER_AGENT_IE7 = "Mozilla/5.0 (Windows; U; MSIE 7.0; Windows NT 6.0; en-US)";
	public static String USER_AGENT_THUNLP = "Mozilla/5.0 (compatible; thunlp-research-exp/1.0; +http://nlp.csai.tsinghua.edu.cn)";
	// parameters
	protected int siteInterval;
	protected String agentString;
	protected int maxConcurrency;
	protected int queueLength;
	protected int connTimeout;
	protected int readTimeout;
	protected List<Header> headers;

	// hash method
	protected HashMethod hashMethod;

	// http client
	protected HttpMethodParams params;
	protected HttpClient httpClient;
	protected HttpConnectionParams connParams;

	// worker threads
	protected ReadWriteLock workersLock;
	protected Hashtable<Integer, CrawlerThread> workers;

	// listeners
	protected InternalCrawlerListener internalListener;
	protected CrawlerListener listener;

	// monitor
	protected ProactiveMonitor monitor;

	// Management reports.
	public static class WorkerStat {
		public int hashId;
		public int numWaitingTask;
		public double meanConnTime;
		public double meanFetchTime;
		public long lifetime;
		public int numFetched;
	}

	public WebCrawler(CrawlerListener listener) {
		this.listener = listener;
		this.internalListener = new InternalCrawlerListener();
		this.monitor = null;

		// build default parameters
		buildDefaultParameters();
	}

	protected void buildDefaultParameters() {
		this.siteInterval = 1000;
		this.agentString = this.USER_AGENT_THUNLP;
		this.maxConcurrency = 60;
		this.queueLength = 10;
		this.connTimeout = 30000;
		this.readTimeout = 60000;
		this.hashMethod = new IpHashMethod();
		this.headers = new LinkedList<Header>();
	}

	public List<WorkerStat> getWorkerStats() {
		List<WorkerStat> stats = new ArrayList<WorkerStat>();
		workersLock.writeLock().lock();
		for (Entry<Integer, CrawlerThread> e : workers.entrySet()) {
			stats.add(e.getValue().getWorkerStat());
		}
		workersLock.writeLock().unlock();
		return stats;
	}

	public void setSiteInterval(int millisec) {
		this.siteInterval = millisec;
	}

	public int getSiteInterval() {
		return siteInterval;
	}

	public void setAgentString(String agent) {
		this.agentString = agent;
	}

	public String getAgentString() {
		return this.agentString;
	}

	public void setConnectionTimeout(int millisec) {
		this.connTimeout = millisec;
	}

	public int getConnectionTimeout() {
		return this.connTimeout;
	}

	public void setReadTimeout(int readTimeout) {
		this.readTimeout = readTimeout;
	}

	public int getReadTimeout() {
		return readTimeout;
	}

	public void setMaxConcurrency(int nthreads) {
		this.maxConcurrency = nthreads;
	}

	public int getMaxConcurrency() {
		return this.maxConcurrency;
	}

	public void setHashMethod(HashMethod method) {
		this.hashMethod = method;
	}

	public HashMethod getHashMethod() {
		return this.hashMethod;
	}

	public void setQueueLength(int length) {
		this.queueLength = length;
	}

	public int getQueueLength() {
		return this.queueLength;
	}

	public void startProactively() {
		start();
		monitor = new ProactiveMonitor(this);
		monitor.start();
	}

	public void start() {
		LOG.log(Level.INFO, "inititalizing crawler");
		// Initialize http client
		httpClient = new HttpClient(new MultiThreadedHttpConnectionManager());
		connParams = httpClient.getHttpConnectionManager().getParams();

		connParams.setParameter(HttpConnectionParams.CONNECTION_TIMEOUT, this.connTimeout);
		params = new HttpMethodParams();
		params.setParameter(HttpMethodParams.USER_AGENT, this.agentString);
		params.setParameter(HttpMethodParams.SO_TIMEOUT, this.getReadTimeout());
		workers = new Hashtable<Integer, CrawlerThread>(this.maxConcurrency);
		workersLock = new ReentrantReadWriteLock();
		LOG.log(Level.INFO, "crawler started");
	}

	public void waitForAll(long millisec) {
		if (monitor != null)
			monitor.shouldStop = true;
		LOG.log(Level.INFO, "waiting for " + workers.size() + "workers to finish");
		if (millisec > 0)
			millisec += System.currentTimeMillis();
		while (workers.size() > 0) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if (millisec > 0 && System.currentTimeMillis() > millisec)
				break;
		}
	}

	public void addRequestHeader(String key, String value) {
		this.headers.add(new Header(key, value));
	}

	public int getCurrentConcurrency() {
		return workers.size();
	}

	protected CrawlerTaskEntry buildTask(String url, String ip, Object customData, boolean internal) {
		GetMethod method = new GetMethod(url);
		method.setParams(params);
		for (Header h : headers) {
			method.addRequestHeader(h);
		}
		if (ip == null) {
			ip = getIpByUrl(url);
		}
		CrawlerTaskEntry task = new CrawlerTaskEntry(url, ip, method, customData);
		task.hashid = hashMethod.hash(url, ip);
		task.internal = internal;
		return task;
	}

	public String getIpByUrl(String url) {
		String ip, host;
		try {
			URL u = new URL(url);
			host = u.getHost();
			ip = InetAddress.getByName(host).getHostAddress();
		} catch (Exception e) {
			LOG.log(Level.WARNING, "cannot resolve " + url);
			return "";
		}
		return ip;
	}

	protected boolean scheduleTask(CrawlerTaskEntry task) {
		workersLock.readLock().lock();
		CrawlerThread worker = workers.get(task.hashid);

		if (worker != null) {
			workersLock.readLock().unlock();
			if (worker.queue.size() > this.queueLength)
				return false;
			worker.submitTask(task);
		} else {
			if (workers.size() > this.maxConcurrency) {
				workersLock.readLock().unlock();
				return false;
			}
			worker = new CrawlerThread(this, task.hashid);
			worker.submitTask(task);
			worker.start();
			workers.put(task.hashid, worker);
			workersLock.readLock().unlock();
		}

		LOG.log(Level.INFO, "task " + task.url + " " + task.ip + "scheduled to worker " + task.hashid);
		return true;
	}

	public void schedule(String url, String ip, Object customData) {
		CrawlerTaskEntry task = null;
		try {
			task = buildTask(url, ip, customData, false);
		} catch (Exception e) {
			e.printStackTrace();
			LOG.info("Cannot schedule task for URL:[" + url + "]");
			return;
		}
		while (!scheduleTask(task)) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	public boolean scheduleNonBlock(String url, String ip, Object customData) {
		CrawlerTaskEntry task = null;
		try {
			task = buildTask(url, ip, customData, false);
		} catch (Exception e) {
			e.printStackTrace();
			LOG.info("Cannot schedule task for URL:[" + url + "]");
			return true;
		}
		return scheduleTask(task);
	}

	public byte[] scheduleAndWait(String url, String ip) {
		return scheduleAndWait(url, ip, 60000);
	}

	public byte[] scheduleAndWait(String url, String ip, long timeout) {
		if (url == null) {
			LOG.info("null url [" + url + "]");
			return null;
		}
		CrawlerTaskEntry task = null;
		try {
			task = buildTask(url, ip, null, true);
		} catch (Exception e) {
			e.printStackTrace();
			LOG.info("Cannot schedule task for URL:[" + url + "]");
		}
		while (!scheduleTask(task)) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		while (!internalListener.storeBox.containsKey(url) && timeout > 0) {
			try {
				Thread.sleep(100);
				timeout -= 100;
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		InternalResult result = internalListener.storeBox.get(url);
		if (result == null) {
			return null;
		}
		byte[] content = internalListener.storeBox.get(url).content;
		internalListener.storeBox.remove(url);
		return content;
	}

	protected class CrawlerTaskEntry {
		public HttpMethod method;
		public Object data;
		public String url;
		public String ip;
		public int hashid;
		public boolean internal;

		public CrawlerTaskEntry(String url, String ip, HttpMethod method, Object data) {
			this.url = url;
			this.method = method;
			this.data = data;
			this.ip = ip;
		}
	}

	protected class ProactiveMonitor extends Thread {
		private WebCrawler parent;
		public boolean shouldStop = false;

		public ProactiveMonitor(WebCrawler parent) {
			this.parent = parent;
		}

		public void run() {
			while (!shouldStop) {
				int capacity = parent.maxConcurrency - parent.workers.size();
				if (capacity > 0) {
					parent.listener.workersAvailable(capacity);
				}
				parent.workersLock.writeLock().lock();
				Iterator<Entry<Integer, CrawlerThread>> iter = parent.workers.entrySet().iterator();
				while (iter.hasNext()) {
					Entry<Integer, CrawlerThread> entry = iter.next();
					capacity = parent.queueLength - entry.getValue().queue.size();
					if (capacity > 0) {
						parent.listener.workerQueueAvailable(entry.getKey(), capacity);
					}
				}
				parent.workersLock.writeLock().unlock();
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {

				}
			}
		}
	}

	protected class CrawlerThread extends Thread {
		private WebCrawler parent;
		private BlockingQueue<CrawlerTaskEntry> queue;
		private int hashid = -1;
		private long birthTime = 0;
		private long totalConnTime = 0;
		private long totalFetchTime = 0;
		private int numFetched = 0;

		public CrawlerThread(WebCrawler parent, int hashid) {
			this.parent = parent;
			this.hashid = hashid;
			this.queue = new LinkedBlockingQueue<CrawlerTaskEntry>(parent.queueLength);
			birthTime = System.currentTimeMillis();
		}

		public void submitTask(CrawlerTaskEntry task) {
			try {
				queue.put(task);
			} catch (InterruptedException e) {
				LOG.log(Level.WARNING, hashid + " submit task failed" + e.getMessage());
			}
		}

		public WorkerStat getWorkerStat() {
			WorkerStat ws = new WorkerStat();
			ws.hashId = this.hashid;
			ws.lifetime = System.currentTimeMillis() - birthTime;
			ws.numWaitingTask = queue.size();
			if (numFetched > 0) {
				ws.meanConnTime = (double) totalConnTime / numFetched;
				ws.meanFetchTime = (double) totalFetchTime / numFetched;
			} else {
				ws.meanConnTime = 0;
				ws.meanFetchTime = 0;
			}
			ws.numFetched = numFetched;
			return ws;
		}

		private void clean() {
			parent.workers.remove(this.hashid);
		}

		public void run() {
			long startTime = 0;
			long endTime = 0;
			// check interval
			while (true) {
				CrawlerTaskEntry task;
				if (queue.size() == 0) {
					parent.workersLock.readLock().lock();
					if (queue.size() == 0) {
						LOG.log(Level.INFO, hashid + " no more task");
						clean();
						parent.workersLock.readLock().unlock();
						return;
					} else {
						parent.workersLock.readLock().unlock();
					}
				}
				try {
					task = queue.take();
					if (task == null) {
						LOG.log(Level.WARNING, hashid + " empty task");
						clean();
						return;
					}
				} catch (InterruptedException e) {
					LOG.log(Level.WARNING, hashid + " interrupted when dequeue " + e.getMessage());
					clean();
					return;
				}

				CrawlerListener listener = (task.internal) ? parent.internalListener : parent.listener;

				if (task.ip.length() < 5) {
					listener.handleFailed(task.url, task.ip, -1, task.data);
				} else {
					boolean success = false;
					byte[] content = null;
					Header[] headers = null;
					String[] responseHeaders = null;
					try {
						LOG.log(Level.INFO, hashid + " fetching " + task.url + " " + task.ip);
						startTime = System.currentTimeMillis();
						parent.httpClient.executeMethod(task.method);
						endTime = System.currentTimeMillis();
						totalConnTime += endTime - startTime;
						startTime = System.currentTimeMillis();
						content = task.method.getResponseBody();
						endTime = System.currentTimeMillis();
						totalFetchTime += endTime - startTime;
						headers = task.method.getResponseHeaders();
						responseHeaders = new String[headers.length];
						for (int i = 0; i < headers.length; i++) {
							responseHeaders[i] = headers[i].getName() + ":" + headers[i].getValue();
						}
						success = true;
					} catch (HttpException e1) {
						LOG.log(Level.WARNING, hashid + " fetch failed " + e1.getClass().getName() + " " + task.url);
					} catch (IOException e1) {
						LOG.log(Level.WARNING, hashid + " fetch failed " + e1.getClass().getName() + " " + task.url);
					} catch (Exception e) {
						LOG.log(Level.WARNING, hashid + " fetch failed " + e.getClass().getName() + " " + task.url);
					} finally {
						try {
							if (success) {
								numFetched++;
								listener.handleSuccess(task.url, task.ip, content, responseHeaders, task.data);
							} else {
								int statusCode = -1;
								try {
									statusCode = task.method.getStatusCode();
								} catch (NullPointerException e) {
									LOG.info("Failed to get the status code.");
								}
								listener.handleFailed(task.url, task.ip, statusCode, task.data);
							}
							task.method.releaseConnection();
						} catch (Exception e) {
							e.printStackTrace();
							LOG.log(Level.WARNING, hashid + " user handler failed for result from " + task.url);
						}
					}
				}
				try {
					Thread.sleep(parent.siteInterval);
				} catch (InterruptedException e) {
					LOG.log(Level.WARNING, hashid + " interrupted when waiting for task " + e.getMessage());
				}
			}
		}

	}
}
