package es.moki.ratelimitj.redis.request;


import es.moki.ratelimitj.core.limiter.request.ReactiveRequestRateLimiter;
import es.moki.ratelimitj.core.limiter.request.RequestLimitRule;
import es.moki.ratelimitj.core.limiter.request.RequestRateLimiter;
import es.moki.ratelimitj.core.time.SystemTimeSupplier;
import es.moki.ratelimitj.core.time.TimeSupplier;
import es.moki.ratelimitj.redis.request.RedisScriptLoader.StoredScript;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

import static io.lettuce.core.ScriptOutputType.VALUE;
import static java.util.Objects.requireNonNull;

@ParametersAreNonnullByDefault
@ThreadSafe
public class RedisSlidingWindowRequestRateLimiter implements RequestRateLimiter, ReactiveRequestRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(RedisSlidingWindowRequestRateLimiter.class);

    private static final Duration BLOCK_TIMEOUT = Duration.of(5, ChronoUnit.SECONDS);

    // TODO on upgrade to Lettuce 5.1.0 check for new RedisNoScriptException
    private static final Predicate<Throwable> STARTS_WITH_NO_SCRIPT_ERROR = e -> e.getMessage().startsWith("NOSCRIPT");

    private final LimitRuleJsonSerialiser serialiser = new LimitRuleJsonSerialiser();


    private final StatefulRedisConnection<String, String> connection;
    private final RedisScriptLoader scriptLoader;
    private final String rulesJson;
    private final TimeSupplier timeSupplier;

    public RedisSlidingWindowRequestRateLimiter(StatefulRedisConnection<String, String> connection, RequestLimitRule rule) {
        this(connection, Collections.singleton(rule));
    }

    public RedisSlidingWindowRequestRateLimiter(StatefulRedisConnection<String, String> connection, Set<RequestLimitRule> rules) {
        this(connection, rules, new SystemTimeSupplier());
    }

    public RedisSlidingWindowRequestRateLimiter(StatefulRedisConnection<String, String> connection, Set<RequestLimitRule> rules, TimeSupplier timeSupplier) {
        requireNonNull(rules, "rules can not be null");
        requireNonNull(timeSupplier, "time supplier can not be null");
        requireNonNull(connection, "connection can not be null");
        this.connection = connection;
        scriptLoader = new RedisScriptLoader(connection, "sliding-window-ratelimit.lua");
        rulesJson = serialiserLimitRules(rules);
        this.timeSupplier = timeSupplier;
    }

    private String serialiserLimitRules(Set<RequestLimitRule> rules) {
        return serialiser.encode(rules);
    }

    @Override
    public boolean overLimitWhenIncremented(String key) {
        return overLimitWhenIncremented(key, 1);
    }

    @Override
    public boolean overLimitWhenIncremented(String key, int weight) {
        return throwOnTimeout(eqOrGeLimitReactive(key, weight, true));
    }

    @Override
    public boolean geLimitWhenIncremented(String key) {
        return geLimitWhenIncremented(key, 1);
    }

    @Override
    public boolean geLimitWhenIncremented(String key, int weight) {
        return throwOnTimeout(eqOrGeLimitReactive(key, weight, false));
    }

//    @Override
//    public boolean isOverLimit(String key) {
//        return overLimitWhenIncremented(key, 0);
//    }
//
//    @Override
//    public boolean isGeLimit(String key) {
//        return geLimitWhenIncremented(key, 0);
//    }

    @Override
    public boolean resetLimit(String key) {
        return throwOnTimeout(resetLimitReactive(key));
    }

    @Override
    public Mono<Boolean> overLimitWhenIncrementedReactive(String key) {
        return overLimitWhenIncrementedReactive(key, 1);
    }

    @Override
    public Mono<Boolean> overLimitWhenIncrementedReactive(String key, int weight) {
        return eqOrGeLimitReactive(key, weight, true);
    }

    @Override
    public Mono<Boolean> geLimitWhenIncrementedReactive(String key) {
        return geLimitWhenIncrementedReactive(key, 1);
    }

    @Override
    public Mono<Boolean> geLimitWhenIncrementedReactive(String key, int weight) {
        return eqOrGeLimitReactive(key, weight, false);
    }

    @Override
    public Mono<Boolean> resetLimitReactive(String key) {
        return connection.reactive().del(key).map(count -> count > 0);
    }

    private CompletionStage<Boolean> eqOrGeLimitAsync(String key, int weight, boolean strictlyGreater) {
        return eqOrGeLimitReactive(key, weight, strictlyGreater).toFuture();
    }

    private Mono<Boolean> eqOrGeLimitReactive(String key, int weight, boolean strictlyGreater) {
        requireNonNull(key);

        return Mono.zip(timeSupplier.getReactive(), scriptLoader.storedScript())
                .flatMapMany(tuple -> {
                    Long time = tuple.getT1();
                    StoredScript script = tuple.getT2();
                    return connection.reactive()
                            .evalsha(script.getSha(), VALUE, new String[]{key}, rulesJson, Long.toString(time), Integer.toString(weight), toStringOneZero(strictlyGreater))
                            .doOnError(STARTS_WITH_NO_SCRIPT_ERROR, e -> script.dispose());
                })
                .retry(1, STARTS_WITH_NO_SCRIPT_ERROR)
                .single()
                .map("1"::equals)
                .doOnSuccess(over -> {
                    if (over) {
                        LOG.debug("Requests matched by key '{}' incremented by weight {} are greater than {}the limit", key, weight, strictlyGreater ? "" : "or equal to ");
                    }
                });
    }

    private String toStringOneZero(boolean strictlyGreater) {
        return strictlyGreater ? "1" : "0";
    }

    private boolean throwOnTimeout(Mono<Boolean> mono) {
        Boolean result = mono.block(BLOCK_TIMEOUT);
        if (result == null) {
            throw new RuntimeException("waited " + BLOCK_TIMEOUT + "before timing out");
        }
        return result;
    }

}
