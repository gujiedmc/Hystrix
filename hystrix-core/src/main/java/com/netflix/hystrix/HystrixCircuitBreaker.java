/**
 * Copyright 2012 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import com.netflix.hystrix.HystrixCommandMetrics.HealthCounts;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hystrix熔断器。
 * 由 {@link Factory} 创建 默认实现 {@link HystrixCircuitBreaker.HystrixCircuitBreakerImpl}
 *
 * Circuit-breaker logic that is hooked into {@link HystrixCommand} execution and will stop allowing executions if failures have gone past the defined threshold.
 * <p>
 * It will then allow single retries after a defined sleepWindow until the execution succeeds at which point it will again close the circuit and allow executions again.
 */
public interface HystrixCircuitBreaker {

    /**
     * 是否允许执行请求。true：说明可以请求，即当前熔断器没有打开，或者打开状态下尝试恢复
     * Every {@link HystrixCommand} requests asks this if it is allowed to proceed or not.
     * <p>
     * This takes into account the half-open logic which allows some requests through when determining if it should be closed again.
     *
     * @return boolean whether a request should be permitted
     */
    public boolean allowRequest();

    /**
     * 查看熔断器是否打卡
     * Whether the circuit is currently open (tripped).
     *
     * @return boolean state of circuit breaker
     */
    public boolean isOpen();

    /**
     * 当请求执行成功时，标记成功，关闭断路器
     * Invoked on successful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     */
    /* package */void markSuccess();

    /**
     * 以Map维护
     *
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    public static class Factory {
        // String is HystrixCommandKey.name() (we can't use HystrixCommandKey directly as we can't guarantee it implements hashcode/equals correctly)
        private static ConcurrentHashMap<String, HystrixCircuitBreaker> circuitBreakersByCommand = new ConcurrentHashMap<String, HystrixCircuitBreaker>();

        /**
         * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey}.
         * <p>
         * This is thread-safe and ensures only 1 {@link HystrixCircuitBreaker} per {@link HystrixCommandKey}.
         *
         * @param key
         *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
         * @param group
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @param properties
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @param metrics
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
         */
        public static HystrixCircuitBreaker getInstance(HystrixCommandKey key, HystrixCommandGroupKey group, HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
            // this should find it for all but the first time
            HystrixCircuitBreaker previouslyCached = circuitBreakersByCommand.get(key.name());
            if (previouslyCached != null) {
                return previouslyCached;
            }

            // if we get here this is the first time so we need to initialize

            // Create and add to the map ... use putIfAbsent to atomically handle the possible race-condition of
            // 2 threads hitting this point at the same time and let ConcurrentHashMap provide us our thread-safety
            // If 2 threads hit here only one will get added and the other will get a non-null response instead.
            HystrixCircuitBreaker cbForCommand = circuitBreakersByCommand.putIfAbsent(key.name(), new HystrixCircuitBreakerImpl(key, group, properties, metrics));
            if (cbForCommand == null) {
                // this means the putIfAbsent step just created a new one so let's retrieve and return it
                return circuitBreakersByCommand.get(key.name());
            } else {
                // this means a race occurred and while attempting to 'put' another one got there before
                // and we instead retrieved it and will now return it
                return cbForCommand;
            }
        }

        /**
         * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey} or null if none exists.
         *
         * @param key
         *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
         * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
         */
        public static HystrixCircuitBreaker getInstance(HystrixCommandKey key) {
            return circuitBreakersByCommand.get(key.name());
        }

        /**
         * Clears all circuit breakers. If new requests come in instances will be recreated.
         */
        /* package */
        static void reset() {
            circuitBreakersByCommand.clear();
        }
    }

    /**
     * 默认实现，基于分析数据{@link HystrixCommandMetrics}和配置信息{@link HystrixCommandProperties}进行熔断判断。
     *
     * The default production implementation of {@link HystrixCircuitBreaker}.
     *
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    /* package */static class HystrixCircuitBreakerImpl implements HystrixCircuitBreaker {
        private final HystrixCommandProperties properties;
        private final HystrixCommandMetrics metrics;

        /**
         * 断路器开启标记。true：开启，false：关闭
         */
        /* track whether this circuit is open/closed at any given point in time (default to false==closed) */
        private AtomicBoolean circuitOpen = new AtomicBoolean(false);

        /**
         * 断路器开启时间，或者开启后最后一次尝试执行请求的时间
         */
        /* when the circuit was marked open or was last allowed to try a 'singleTest' */
        private AtomicLong circuitOpenedOrLastTestedTime = new AtomicLong();

        protected HystrixCircuitBreakerImpl(HystrixCommandKey key, HystrixCommandGroupKey commandGroup, HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
            this.properties = properties;
            this.metrics = metrics;
        }

        /**
         * 当执行请求成功之后，设置标记为关闭状态，并重置统计信息。
         */
        public void markSuccess() {
            if (circuitOpen.get()) {
                if (circuitOpen.compareAndSet(true, false)) {
                    // 重置统计信息
                    //win the thread race to reset metrics
                    //Unsubscribe from the current stream to reset the health counts stream.  This only affects the health counts view,
                    //and all other metric consumers are unaffected by the reset
                    metrics.resetStream();
                }
            }
        }

        /**
         * 判断是否允许请求。以下3种情况允许请求
         * 1. 断路器被配置强制关闭
         * 2. 断路器未打开
         * 3. 断路器已打开，但是出于半打开状态，尝试执行一次请求查看下游服务是否恢复
         *
         * @return
         */
        @Override
        public boolean allowRequest() {
            // 断路器已经打开
            if (properties.circuitBreakerForceOpen().get()) {
                // properties have asked us to force the circuit open so we will allow NO requests
                return false;
            }
            // 断路器强制关闭时，直接返回true
            if (properties.circuitBreakerForceClosed().get()) {
                // 这里任然会执行判断熔断器是否打开的逻辑，只是用来正常跑流程
                // we still want to allow isOpen() to perform it's calculations so we simulate normal behavior
                isOpen();
                // properties have asked us to ignore errors so we will ignore the results of isOpen and just allow all traffic through
                return true;
            }
            // 熔断器未打开，或者半打开状态尝试恢复
            return !isOpen() || allowSingleTest();
        }

        /**
         * 查看是否可以尝试单个请求测试下游服务是否恢复
         * 结果 = 断路器当前打开 &&  （当前时间- 上次尝试的时间或者断路器打开的时间 > 用户设置的断路器窗口时间（默认5s））
         *          && cas修改最后更新时间成功（失败说明其他线程获取到尝试机会）
         * 如果判定成功，返回true执行单次执行。并设置断路器时间
         * @return
         */
        public boolean allowSingleTest() {
            long timeCircuitOpenedOrWasLastTested = circuitOpenedOrLastTestedTime.get();
            // 1) if the circuit is open
            // 2) and it's been longer than 'sleepWindow' since we opened the circuit
            if (circuitOpen.get() && System.currentTimeMillis() > timeCircuitOpenedOrWasLastTested + properties.circuitBreakerSleepWindowInMilliseconds().get()) {
                // 设置最新尝试时间
                // We push the 'circuitOpenedTime' ahead by 'sleepWindow' since we have allowed one request to try.
                // If it succeeds the circuit will be closed, otherwise another singleTest will be allowed at the end of the 'sleepWindow'.
                if (circuitOpenedOrLastTestedTime.compareAndSet(timeCircuitOpenedOrWasLastTested, System.currentTimeMillis())) {
                    // if this returns true that means we set the time so we'll return true to allow the singleTest
                    // if it returned false it means another thread raced us and allowed the singleTest before we did
                    return true;
                }
            }
            return false;
        }

        /**
         * 查看断路器是否打开。如果已经打开，直接返回，否则通过统计信息进行计算是否需要打开断路器
         *
         * @return true:已经短路，断路器打开。false：没有短路，断路器关闭。
         */
        @Override
        public boolean isOpen() {
            // 1. 如果已经被标记打开的话，直接短路
            if (circuitOpen.get()) {
                // if we're open we immediately return true and don't bother attempting to 'close' ourself as that is left to allowSingleTest and a subsequent successful test to close
                return true;
            }

            // we're closed, so let's see if errors have made us so we should trip the circuit open
            HealthCounts health = metrics.getHealthCounts();

            // 2. 检查最小请求量（默认10s内的最小请求量），如果小于设置下限，不断路
            // check if we are past the statisticalWindowVolumeThreshold
            if (health.getTotalRequests() < properties.circuitBreakerRequestVolumeThreshold().get()) {
                // we are not past the minimum volume threshold for the statisticalWindow so we'll return false immediately and not calculate anything
                return false;
            }
            // 3. 检查异常比例，如果异常比例小于设置的比例，不断路
            if (health.getErrorPercentage() < properties.circuitBreakerErrorThresholdPercentage().get()) {
                return false;
            } else {
                // 4. 断路，设置标志，设置断路的时间
                // our failure rate is too high, trip the circuit
                if (circuitOpen.compareAndSet(false, true)) {
                    // 打开成功的话，设置断路器打开或者最后一次尝试请求的时间，这个时间会被用来在尝试关闭断路器{@link #allowSingleTest}时使用
                    // if the previousValue was false then we want to set the currentTime
                    circuitOpenedOrLastTestedTime.set(System.currentTimeMillis());
                    return true;
                } else {
                    // 说明此时已有其它线程将断路器打开
                    // How could previousValue be true? If another thread was going through this code at the same time a race-condition could have
                    // caused another thread to set it to true already even though we were in the process of doing the same
                    // In this case, we know the circuit is open, so let the other thread set the currentTime and report back that the circuit is open
                    return true;
                }
            }
        }

    }

    /**
     * 不需要任何逻辑实现的断路器，全部放行。
     * An implementation of the circuit breaker that does nothing.
     *
     * @ExcludeFromJavadoc
     */
    /* package */static class NoOpCircuitBreaker implements HystrixCircuitBreaker {

        @Override
        public boolean allowRequest() {
            return true;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void markSuccess() {

        }

    }

}
