package ai.pipestream.quarkus.dynamicgrpc.discovery;

import io.smallrye.stork.api.LoadBalancer;
import io.smallrye.stork.api.ServiceInstance;

import java.util.Collection;
import java.util.List;
import java.util.Random;

/**
 * Simple random load balancer implementation for Stork.
 */
public class RandomLoadBalancer implements LoadBalancer {

    /**
     * Creates a new RandomLoadBalancer.
     */
    public RandomLoadBalancer() {
    }

    private final Random random = new Random();

    /**
     * Selects a service instance at random from the provided collection.
     * If only one instance is available, that instance is returned.
     *
     * @param instances the available instances to pick from (must not be null or empty)
     * @return the randomly selected instance
     * @throws IllegalArgumentException if {@code instances} is null or empty
     */
    @Override
    public ServiceInstance selectServiceInstance(Collection<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("No instances available for selection");
        }

        List<ServiceInstance> instanceList = instances instanceof List ?
                (List<ServiceInstance>) instances : new java.util.ArrayList<>(instances);

        if (instanceList.size() == 1) {
            return instanceList.getFirst();
        }

        int index = random.nextInt(instanceList.size());
        return instanceList.get(index);
    }
}
