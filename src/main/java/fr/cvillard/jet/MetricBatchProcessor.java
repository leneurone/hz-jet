package fr.cvillard.jet;

import com.hazelcast.core.IMap;
import com.hazelcast.jet.IMapJet;
import com.hazelcast.jet.Jet;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.pipeline.BatchStage;
import com.hazelcast.jet.pipeline.JoinClause;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.Sources;
import com.hazelcast.logging.ILogger;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Perform simple Customer enrichment on a given batch of Metrics, stored on a Map.
 */
public class MetricBatchProcessor {
	/**
	 * Number of generated metrics on input
	 */
	private static final int NB_ITEMS = 1_000_000;

	/**
	 * Number of Customer known in the Customer's map
	 */
	private static final int NB_CUSTOMERS = 20;

	/**
	 * The name of the input map, storing the raw Metrics
	 */
	private static final String SOURCE_MAP_NAME = "sourceMap";

	/**
	 * The name of the output map, storing the enriched metrics
	 */
	private static final String OUTPUT_MAP_NAME = "outputMap";

	/**
	 * The name of the error output map, storing the metrics that could not be processed
	 */
	private static final String ERROR_OUTPUT_MAP_NAME = "errorOutputMap";

	/**
	 * The name of the map storing the customers
	 */
	private static final String CUSTOMER_MAP_NAME = "customers";

	/**
	 * Launch Jet instance, populate the maps, launch the batch Job to process metrics and check output
	 *
	 * @param args unused
	 * @throws ExecutionException   if an error occur during Jet job execution
	 * @throws InterruptedException if Jet job or wait on counter of processed elements got interrupted
	 */
	public static void main(String[] args) throws ExecutionException, InterruptedException {

		// launch Jet with default configuration
		JetInstance jet = Jet.newJetInstance();

		// stop jet on termination
		Runtime.getRuntime().addShutdownHook(new Thread(Jet::shutdownAll));

		// Create an additional instance; it will automatically discover the first one and form a cluster
		Jet.newJetInstance();

		// get Logger from Hazelcast for simplicity purpose
		ILogger logger = jet.getHazelcastInstance().getLoggingService().getLogger(MetricBatchProcessor.class);

		// preload map of customer for enrichment
		IMap<Integer, Customer> customerMap = jet.getHazelcastInstance().getMap(CUSTOMER_MAP_NAME);
		for (int i = 0; i < NB_CUSTOMERS; i++) {
			customerMap.put(i, new Customer(i, "Customer " + i));
		}

		// preload map of metrics
		IMapJet<String, Metric> inputMap = jet.getMap(SOURCE_MAP_NAME);
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		// initialize timestamp to now - 5s
		final long startTimeMs = System.currentTimeMillis() - 5 * 1000;
		// parallel map loading to fully use CPU
		int nbCPUs = Runtime.getRuntime().availableProcessors();
		ExecutorService service = Executors.newFixedThreadPool(nbCPUs * 2);
		List<Future> futures = new ArrayList<>(10);
		// submit tasks of 10.000 items each
		int maxIdx = -1;
		while (maxIdx < NB_ITEMS) {
			int startIdx = maxIdx + 1;
			// intermediate variable localMaxIdx is necessary to have effectively final variable in lambda expression
			int localMaxIdx = startIdx + Math.min(100_000, NB_ITEMS);
			maxIdx = localMaxIdx;
			futures.add(service.submit(() -> {
				for (int i = startIdx; i < localMaxIdx; i++) {
					// add 1 to avoid nbCalls to be equal to 0, which is not supported as bound for rnd.nextInt below
					final int nbCalls = rnd.nextInt(10) + 1;
					final long metricTimeMs = startTimeMs - i; // ensure there is no collision since timestamp is the key on the input map
					// we voluntarily generate some metrics with non existing customers to see error output in action
					inputMap.set(Long.toString(metricTimeMs), new Metric(metricTimeMs, rnd.nextInt(NB_CUSTOMERS + 5),
							nbCalls, rnd.nextInt(nbCalls)));
				}
			}));
		}

		for (Future fut : futures) {
			fut.get(); // wait for init completion
		}

		logger.info("Initial loading done, will start Jet Job...");

		// treatment pipeline
		Pipeline p = Pipeline.create();
		// prepare customer enrichment
		// WARN: the full content of this map will be processed and stored in hashmaps on all the nodes, to make the hash join fast
		// do not forget to consider this when estimating RAM consumption of the Job
		BatchStage<Map.Entry<Integer, Customer>> customerEntries = p.drawFrom(Sources.map(CUSTOMER_MAP_NAME));
		// this standard source does NOT remove items from input map, so you have to do it yourself when it suits you
		BatchStage<EnrichedMetric> enrichedStage = p
				.drawFrom(Sources.<String, Metric>map(SOURCE_MAP_NAME))
				// transform map entries to values since we do not need the key
				.map(Map.Entry::getValue)
				// enrich metrics with customers and transform them to EnrichedMetric
				.hashJoin(customerEntries, JoinClause.joinMapEntries(Metric::getCustomerId), EnrichedMetric::new);

		// normal workflow
		enrichedStage
				// filter valid data
				.filter((EnrichedMetric::isEnriched))
				// transform EnrichedMetric to Map entry
				.map(m -> new AbstractMap.SimpleEntry<>(m.getMetric().getTimestampMs(), m))
				// output to map
				.drainTo(Sinks.map(OUTPUT_MAP_NAME));

		// output errors to another map
		enrichedStage
				// filter error data
				.filter(enrichedMetric -> !enrichedMetric.isEnriched())
				// transform to map entry
				.map(m -> new AbstractMap.SimpleEntry<>(m.getMetric().getTimestampMs(), m.getMetric()))
				// output to error map
				.drainTo(Sinks.map(ERROR_OUTPUT_MAP_NAME));

		// execute the graph and wait for completion
		jet.newJob(p).join();

		// get output maps size
		final int normalCount = jet.getMap(OUTPUT_MAP_NAME).size();
		final int errorCount = jet.getMap(ERROR_OUTPUT_MAP_NAME).size();

		logger.info("Received items (normal / error / total): " + normalCount + " / " + errorCount + " / " + (normalCount + errorCount));

		// Shutdown Jet instances
		Jet.shutdownAll();

		// exit cleanly
		System.exit(0);
	}
}
