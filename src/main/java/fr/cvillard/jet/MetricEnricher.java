package fr.cvillard.jet;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.jet.core.AbstractProcessor;

import javax.annotation.Nonnull;
import java.util.AbstractMap;

public class MetricEnricher extends AbstractProcessor {

	/**
	 * Id of output edge to normal output processor
	 */
	public static final int NORMAL_OUTPUT_ORDINAL = 0;

	/**
	 * Id of output edge to error output processor
	 */
	public static final int ERROR_OUTPUT_ORDINAL = 1;

	/**
	 * the map of Customers
	 */
	private IMap<Integer, Customer> customerMap;

	/**
	 * The name of the map of customers
	 */
	private String customerMapName;

	@Override
	protected void init(@Nonnull Context context) {
		HazelcastInstance hzInstance = context.jetInstance().getHazelcastInstance();
		customerMap = hzInstance.getMap(customerMapName);
	}

	public MetricEnricher(String customerMapName) {
		this.customerMapName = customerMapName;
	}

	@Override
	protected boolean tryProcess0(@Nonnull Object item) {
		// since the source vertex reads a map, the item must be of Map.Entry type
		if (item instanceof Metric) {
			Metric metric = (Metric) item;
			Customer customer = customerMap.get(metric.getCustomerId());
			if (customer != null) {
				// the tryEmit method returns false if the outbox is full and can not accept the item.
				// the engine will take responsibility of flushing the outbox, and returning false on this method
				// will make it recall the method later to retry the processing of the item
				return tryEmit(NORMAL_OUTPUT_ORDINAL, new EnrichedMetric(metric, customer));
			} else {
				// customer not found
				return tryEmit(ERROR_OUTPUT_ORDINAL, new AbstractMap.SimpleEntry<>(metric.getTimestampMs(), metric));
			}
		} else {
			return tryEmit(ERROR_OUTPUT_ORDINAL, new AbstractMap.SimpleEntry<>(System.currentTimeMillis(), item));
		}
	}
}
