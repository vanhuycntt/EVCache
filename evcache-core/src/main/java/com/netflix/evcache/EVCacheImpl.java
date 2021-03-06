package com.netflix.evcache;

import static com.netflix.evcache.util.Sneaky.sneakyThrow;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.archaius.api.Property;
import com.netflix.archaius.api.PropertyRepository;
import com.netflix.evcache.EVCache.Call;
import com.netflix.evcache.EVCacheInMemoryCache.DataNotFoundException;
import com.netflix.evcache.EVCacheLatch.Policy;
import com.netflix.evcache.event.EVCacheEvent;
import com.netflix.evcache.event.EVCacheEventListener;
import com.netflix.evcache.metrics.EVCacheMetricsFactory;
import com.netflix.evcache.operation.EVCacheFuture;
import com.netflix.evcache.operation.EVCacheItem;
import com.netflix.evcache.operation.EVCacheItemMetaData;
import com.netflix.evcache.operation.EVCacheLatchImpl;
import com.netflix.evcache.operation.EVCacheOperationFuture;
import com.netflix.evcache.pool.EVCacheClient;
import com.netflix.evcache.pool.EVCacheClientPool;
import com.netflix.evcache.pool.EVCacheClientPoolManager;
import com.netflix.evcache.pool.EVCacheClientUtil;
import com.netflix.evcache.pool.EVCacheValue;
import com.netflix.evcache.pool.ServerGroup;
import com.netflix.evcache.util.KeyHasher;
import com.netflix.spectator.api.BasicTag;
import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.DistributionSummary;
import com.netflix.spectator.api.Tag;
import com.netflix.spectator.api.Timer;

import net.spy.memcached.CachedData;
import net.spy.memcached.transcoders.Transcoder;
import rx.Observable;
import rx.Scheduler;
import rx.Single;

/**
 * An implementation of a ephemeral volatile cache.
 *
 * @author smadappa
 * @version 2.0
 */

@SuppressWarnings("unchecked")
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings({ "PRMC_POSSIBLY_REDUNDANT_METHOD_CALLS", "WMI_WRONG_MAP_ITERATOR",
    "DB_DUPLICATE_BRANCHES", "REC_CATCH_EXCEPTION","RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE" })
final public class EVCacheImpl implements EVCache, EVCacheImplMBean {

    private static final Logger log = LoggerFactory.getLogger(EVCacheImpl.class);

    private final String _appName;
    private final String _cacheName;
    private final String _metricPrefix;
    private final Transcoder<?> _transcoder;
    private final boolean _zoneFallback;
    private final boolean _throwException;

    private final int _timeToLive; // defaults to 15 minutes
    private EVCacheClientPool _pool;

    private final Property<Boolean> _throwExceptionFP, _zoneFallbackFP, _useInMemoryCache;
    private final Property<Boolean> _bulkZoneFallbackFP;
    private final Property<Boolean> _bulkPartialZoneFallbackFP;
    private final List<Tag> tags;
    private EVCacheInMemoryCache<?> cache;
    private EVCacheClientUtil clientUtil = null;
    private final Property<Boolean> ignoreTouch;
    private final Property<Boolean> hashKey;
    private final Property<String> hashingAlgo;
    private final EVCacheTranscoder evcacheValueTranscoder;
    private final Property<Integer> maxReadDuration, maxWriteDuration;

    private final EVCacheClientPoolManager _poolManager;
    private final Map<String, Timer> timerMap = new ConcurrentHashMap<String, Timer>();
    private final Map<String, DistributionSummary> distributionSummaryMap = new ConcurrentHashMap<String, DistributionSummary>();
    private final Map<String, Counter> counterMap = new ConcurrentHashMap<String, Counter>();
    private final Property<Boolean> _eventsUsingLatchFP, autoHashKeys;
    private DistributionSummary bulkKeysSize = null;

    private final Property<Integer> maxKeyLength;
    private final Property<String> alias;

    EVCacheImpl(String appName, String cacheName, int timeToLive, Transcoder<?> transcoder, boolean enableZoneFallback,
            boolean throwException, EVCacheClientPoolManager poolManager) {
        this._appName = appName;
        this._cacheName = cacheName;

        if(_cacheName != null && _cacheName.length() > 0) {
            for(int i = 0; i < cacheName.length(); i++) {
                if(Character.isWhitespace(cacheName.charAt(i))){
                    throw new IllegalArgumentException("Cache Prefix ``" + cacheName  + "`` contains invalid character at position " + i );
                }
            }
        }

        this._timeToLive = timeToLive;
        this._transcoder = transcoder;
        this._zoneFallback = enableZoneFallback;
        this._throwException = throwException;

        tags = new ArrayList<Tag>(3);
        EVCacheMetricsFactory.getInstance().addAppNameTags(tags, _appName);
        if(_cacheName != null && _cacheName.length() > 0) tags.add(new BasicTag(EVCacheMetricsFactory.PREFIX, _cacheName));

        final String _metricName = (_cacheName == null) ? _appName : _appName + "." + _cacheName;
        _metricPrefix = _appName + "-";
        this._poolManager = poolManager;
        this._pool = poolManager.getEVCacheClientPool(_appName);
        final PropertyRepository propertyRepository = poolManager.getEVCacheConfig().getPropertyRepository();
        _throwExceptionFP = propertyRepository.get(_metricName + ".throw.exception", Boolean.class).orElseGet(_appName + ".throw.exception").orElse(false);
        _zoneFallbackFP = propertyRepository.get(_metricName + ".fallback.zone", Boolean.class).orElseGet(_appName + ".fallback.zone").orElse(true);
        _bulkZoneFallbackFP = propertyRepository.get(_appName + ".bulk.fallback.zone", Boolean.class).orElse(true);
        _bulkPartialZoneFallbackFP = propertyRepository.get(_appName+ ".bulk.partial.fallback.zone", Boolean.class).orElse(true);
        if(_cacheName == null) {
            _useInMemoryCache = propertyRepository.get(_appName + ".use.inmemory.cache", Boolean.class).orElseGet("evcache.use.inmemory.cache").orElse(false);
        } else {
            _useInMemoryCache = propertyRepository.get(_appName + "." + _cacheName + ".use.inmemory.cache", Boolean.class).orElseGet(_appName + ".use.inmemory.cache").orElseGet("evcache.use.inmemory.cache").orElse(false);
        }
        _eventsUsingLatchFP = propertyRepository.get(_appName + ".events.using.latch", Boolean.class).orElseGet("evcache.events.using.latch").orElse(false);
         maxReadDuration = propertyRepository.get(_appName + ".max.read.duration.metric", Integer.class).orElseGet("evcache.max.write.duration.metric").orElse(20);
         maxWriteDuration = propertyRepository.get(_appName + ".max.write.duration.metric", Integer.class).orElseGet("evcache.max.write.duration.metric").orElse(50);
         ignoreTouch = propertyRepository.get(appName + ".ignore.touch", Boolean.class).orElse(false);


        this.hashKey = propertyRepository.get(appName + ".hash.key", Boolean.class).orElse(false);
        this.hashingAlgo = propertyRepository.get(appName + ".hash.algo", String.class).orElse("siphash24");
        this.autoHashKeys = propertyRepository.get(_appName + ".auto.hash.keys", Boolean.class).orElseGet("evcache.auto.hash.keys").orElse(false);
        this.evcacheValueTranscoder = new EVCacheTranscoder();
        evcacheValueTranscoder.setCompressionThreshold(Integer.MAX_VALUE);

        // default max key length is 200, instead of using what is defined in MemcachedClientIF.MAX_KEY_LENGTH (250). This is to accommodate
        // auto key prepend with appname for duet feature.
        this.maxKeyLength = propertyRepository.get(_appName + ".max.key.length", Integer.class).orElseGet("evcache.max.key.length").orElse(200);

        // if alias changes, refresh my pool to point to the correct alias app
        this.alias = propertyRepository.get("EVCacheClientPoolManager." + appName + ".alias", String.class);
        this.alias.subscribe(i -> {
            this._pool = poolManager.getEVCacheClientPool(_appName);
        });

        _pool.pingServers();
        
        setupMonitoring();
    }
    
    private void setupMonitoring() {
        try {
            final ObjectName mBeanName = ObjectName.getInstance("com.netflix.evcache:Group=" + _appName
                    + ",SubGroup=Impl");
            final MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
            if (mbeanServer.isRegistered(mBeanName)) {
                if (log.isDebugEnabled()) log.debug("MBEAN with name " + mBeanName + " has been registered. Will unregister the previous instance and register a new one.");
                mbeanServer.unregisterMBean(mBeanName);
            }
            mbeanServer.registerMBean(this, mBeanName);
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("Exception", e);
        }
    }

    

    EVCacheKey getEVCacheKey(final String key) {
        if(key == null || key.length() == 0) throw new NullPointerException("Key cannot be null or empty");
        for(int i = 0; i < key.length(); i++) {
            if(Character.isWhitespace(key.charAt(i))){
                throw new IllegalArgumentException("key ``" + key + "`` contains invalid character at position " + i );
            }
        }

        final String canonicalKey;
        if (this._cacheName == null) {
            canonicalKey = key;
        } else {
            final int keyLength = _cacheName.length() + 1 + key.length();
            canonicalKey  = new StringBuilder(keyLength).append(_cacheName).append(':').append(key).toString();
        }

        final String hashedKey;
        if(hashKey.get()) {
            hashedKey = KeyHasher.getHashedKey(canonicalKey, hashingAlgo.get());
        } else if(autoHashKeys.get() && canonicalKey.length() > this.maxKeyLength.get()) {
            hashedKey = KeyHasher.getHashedKey(canonicalKey, hashingAlgo.get());
        } else {
            hashedKey = null;
        }

        if (hashedKey == null && canonicalKey.length() > this.maxKeyLength.get()) {
            throw new IllegalArgumentException("Key is too long (maxlen = " + this.maxKeyLength.get() + ')');
        }

        final EVCacheKey evcKey = new EVCacheKey(_appName, key, canonicalKey, hashedKey, hashingAlgo);
        if (log.isDebugEnabled() && shouldLog()) log.debug("Key : " + key + "; EVCacheKey : " + evcKey);
        return evcKey;
    }

    private boolean hasZoneFallbackForBulk() {
        if (!_pool.supportsFallback()) return false;
        if (!_bulkZoneFallbackFP.get()) return false;
        return _zoneFallback;
    }

    private boolean hasZoneFallback() {
        if (!_pool.supportsFallback()) return false;
        if (!_zoneFallbackFP.get().booleanValue()) return false;
        return _zoneFallback;
    }

    private boolean shouldLog() {
        return _poolManager.shouldLog(_appName);
    }

    private boolean doThrowException() {
        return (_throwException || _throwExceptionFP.get().booleanValue());
    }

    private List<EVCacheEventListener> getEVCacheEventListeners() {
        return _poolManager.getEVCacheEventListeners();
    }

    private EVCacheEvent createEVCacheEvent(Collection<EVCacheClient> clients, Call call) {
        final List<EVCacheEventListener> evcacheEventListenerList = getEVCacheEventListeners();
        if (evcacheEventListenerList == null || evcacheEventListenerList.size() == 0) return null;

        final EVCacheEvent event = new EVCacheEvent(call, _appName, _cacheName, _pool);
        event.setClients(clients);
        return event;
    }

    private boolean shouldThrottle(EVCacheEvent event) throws EVCacheException {
        for (EVCacheEventListener evcacheEventListener : getEVCacheEventListeners()) {
            try {
                if (evcacheEventListener.onThrottle(event)) {
                    return true;
                }
            } catch(Exception e) {
                incrementEventFailure("throttle", event.getCall(), evcacheEventListener.getClass().getName());
                if (log.isDebugEnabled() && shouldLog()) log.debug("Exception executing throttle event on listener " + evcacheEventListener + " for event " + event, e);
            }
        }
        return false;
    }

    private void startEvent(EVCacheEvent event) {
        final List<EVCacheEventListener> evcacheEventListenerList = getEVCacheEventListeners();
        for (EVCacheEventListener evcacheEventListener : evcacheEventListenerList) {
            try {
                evcacheEventListener.onStart(event);
            } catch(Exception e) {
                incrementEventFailure("start", event.getCall(), evcacheEventListener.getClass().getName());
                if (log.isDebugEnabled() && shouldLog()) log.debug("Exception executing start event on listener " + evcacheEventListener + " for event " + event, e);
            }
        }
    }

    private void endEvent(EVCacheEvent event) {
        event.setEndTime(System.currentTimeMillis());
        final List<EVCacheEventListener> evcacheEventListenerList = getEVCacheEventListeners();
        for (EVCacheEventListener evcacheEventListener : evcacheEventListenerList) {
            try {
                evcacheEventListener.onComplete(event);
            } catch(Exception e) {
                incrementEventFailure("end", event.getCall(), evcacheEventListener.getClass().getName());
                if (log.isDebugEnabled() && shouldLog()) log.debug("Exception executing end event on listener " + evcacheEventListener + " for event " + event, e);
            }
        }
    }

    private void eventError(EVCacheEvent event, Throwable t) {
        event.setEndTime(System.currentTimeMillis());
        final List<EVCacheEventListener> evcacheEventListenerList = getEVCacheEventListeners();
        for (EVCacheEventListener evcacheEventListener : evcacheEventListenerList) {
            try {
                evcacheEventListener.onError(event, t);
            } catch(Exception e) {
                incrementEventFailure("error", event.getCall(), evcacheEventListener.getClass().getName());
                if (log.isDebugEnabled() && shouldLog()) log.debug("Exception executing error event on listener " + evcacheEventListener + " for event " + event, e);
            }
        }
    }

    private <T> EVCacheInMemoryCache<T> getInMemoryCache(Transcoder<T> tc) {
        if (cache == null) cache = _poolManager.createInMemoryCache(tc, this);
        return (EVCacheInMemoryCache<T>) cache;
    }

    public <T> T get(String key) throws EVCacheException {
        return this.get(key, (Transcoder<T>) _transcoder);
    }

    private void incrementFastFail(String metric, Call call) {
        final String name = metric + call.name();
        Counter counter = counterMap.get(name);
        if(counter == null) {
            final List<Tag> tagList = new ArrayList<Tag>(tags.size() + 3);
            tagList.addAll(tags);
            if(call != null) {
                final String operation = call.name();
                final String operationType;
                switch(call) {
                    case GET:
                    case GET_AND_TOUCH:
                    case GETL:
                    case BULK:
                    case ASYNC_GET:
                        operationType = EVCacheMetricsFactory.READ;
                        break;
                    default :
                        operationType = EVCacheMetricsFactory.WRITE;
                }
                if(operation != null) tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TAG, operation));
                if(operationType != null) tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TYPE_TAG, operationType));
            }
            tagList.add(new BasicTag(EVCacheMetricsFactory.FAILURE_REASON, metric));
            counter = EVCacheMetricsFactory.getInstance().getCounter(EVCacheMetricsFactory.FAST_FAIL, tagList);
            counterMap.put(name, counter);
        }
        counter.increment();
    }

    private void incrementEventFailure(String metric, Call call, String event) {
        final String name = metric + call.name() + event;
        Counter counter = counterMap.get(name);
        if(counter == null) {
            final List<Tag> tagList = new ArrayList<Tag>(tags.size() + 3);
            tagList.addAll(tags);
            if(call != null) {
                final String operation = call.name();
                final String operationType;
                switch(call) {
                case GET:
                case GET_AND_TOUCH:
                case GETL:
                case BULK:
                case ASYNC_GET:
                    operationType = EVCacheMetricsFactory.READ;
                    break;
                default :
                    operationType = EVCacheMetricsFactory.WRITE;
                }
                if(operation != null) tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TAG, operation));
                if(operationType != null) tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TYPE_TAG, operationType));
            }
            tagList.add(new BasicTag(EVCacheMetricsFactory.EVENT_STAGE, metric));
            tagList.add(new BasicTag(EVCacheMetricsFactory.EVENT, event));
            counter = EVCacheMetricsFactory.getInstance().getCounter(EVCacheMetricsFactory.INTERNAL_EVENT_FAIL, tagList);
            counterMap.put(name, counter);
        }
        counter.increment();
    }

    private void incrementFailure(String metric, String operation, String operationType) {
        final String name = metric + operation;
        Counter counter = counterMap.get(name);
        if(counter == null) {
            final List<Tag> tagList = new ArrayList<Tag>(tags.size() + 3);
            tagList.addAll(tags);
            if(operation != null) tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TAG, operation));
            if(operationType != null) tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TYPE_TAG, operationType));
            tagList.add(new BasicTag(EVCacheMetricsFactory.FAILURE_REASON, metric));
            counter = EVCacheMetricsFactory.getInstance().getCounter(EVCacheMetricsFactory.INTERNAL_FAIL, tagList);
            counterMap.put(name, counter);
        }
        counter.increment();
    }

    public <T> T get(String key, Transcoder<T> tc) throws EVCacheException {
        if (null == key) throw new IllegalArgumentException("Key cannot be null");
        final EVCacheKey evcKey = getEVCacheKey(key);
        if (_useInMemoryCache.get()) {
            T value = null;
            try {
                final Transcoder<T> transcoder = (tc == null) ? ((_transcoder == null) ? (Transcoder<T>) _pool.getEVCacheClientForRead().getTranscoder() : (Transcoder<T>) _transcoder) : tc;
                value = (T) getInMemoryCache(transcoder).get(evcKey);
            } catch (ExecutionException e) {
                final boolean throwExc = doThrowException();
                if(throwExc) {
                    if(e.getCause() instanceof DataNotFoundException) {
                        return null;
                    }
                    if(e.getCause() instanceof EVCacheException) {
                        if (log.isDebugEnabled() && shouldLog()) log.debug("ExecutionException while getting data from InMemory Cache", e);
                        throw (EVCacheException)e.getCause();
                    }
                    throw new EVCacheException("ExecutionException", e);
                }
            }
            if (log.isDebugEnabled() && shouldLog()) log.debug("Value retrieved from inmemory cache for APP " + _appName + ", key : " + evcKey + (log.isTraceEnabled() ? "; value : " + value : ""));
            if (value != null) {
                if (log.isDebugEnabled() && shouldLog()) log.debug("Value retrieved from inmemory cache for APP " + _appName + ", key : " + evcKey + (log.isTraceEnabled() ? "; value : " + value : ""));
                return value;
            } else {
                if (log.isInfoEnabled() && shouldLog()) log.info("Value not_found in inmemory cache for APP " + _appName + ", key : " + evcKey + "; value : " + value );
            }
        }
        return doGet(evcKey, tc);
    }

    <T> T doGet(EVCacheKey evcKey , Transcoder<T> tc) throws EVCacheException {
        final boolean throwExc = doThrowException();
        EVCacheClient client = _pool.getEVCacheClientForRead();
        if (client == null) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.GET);
            if (throwExc) throw new EVCacheException("Could not find a client to get the data APP " + _appName);
            return null; // Fast failure
        }

        final EVCacheEvent event = createEVCacheEvent(Collections.singletonList(client), Call.GET);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.GET);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + evcKey);
                    return null;
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.GET);
                return null;
            }
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String status = EVCacheMetricsFactory.SUCCESS;
        String cacheOperation = EVCacheMetricsFactory.YES;
        int tries = 1;
        try {
            final boolean hasZF = hasZoneFallback();
            boolean throwEx = hasZF ? false : throwExc;
            T data = getData(client, evcKey, tc, throwEx, hasZF);
            if (data == null && hasZF) {
                final List<EVCacheClient> fbClients = _pool.getEVCacheClientsForReadExcluding(client.getServerGroup());
                if (fbClients != null && !fbClients.isEmpty()) {
                    for (int i = 0; i < fbClients.size(); i++) {
                        final EVCacheClient fbClient = fbClients.get(i);
                        if(i >= fbClients.size() - 1) throwEx = throwExc;
                        if (event != null) {
                            try {
                                if (shouldThrottle(event)) {
                                    status = EVCacheMetricsFactory.THROTTLED;
                                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + evcKey);
                                    return null;
                                }
                            } catch(EVCacheException ex) {
                                if(throwExc) throw ex;
                                status = EVCacheMetricsFactory.THROTTLED;
                                return null;
                            }
                        }
                        tries++;
                        data = getData(fbClient, evcKey, tc, throwEx, (i < fbClients.size() - 1) ? true : false);
                        if (log.isDebugEnabled() && shouldLog()) log.debug("Retry for APP " + _appName + ", key [" + evcKey + (log.isTraceEnabled() ? "], Value [" + data : "") + "], ServerGroup : " + fbClient.getServerGroup());
                        if (data != null) {
                            client = fbClient;
                            break;
                        }
                    }
                }
            }
            if (data != null) {
                if (event != null) event.setAttribute("status", "GHIT");
            } else {
                cacheOperation = EVCacheMetricsFactory.NO;
                if (event != null) event.setAttribute("status", "GMISS");
                if (log.isInfoEnabled() && shouldLog()) log.info("GET : APP " + _appName + " ; cache miss for key : " + evcKey);
            }
            if (log.isDebugEnabled() && shouldLog()) log.debug("GET : APP " + _appName + ", key [" + evcKey + (log.isTraceEnabled() ? "], Value [" + data : "") + "], ServerGroup : " + client.getServerGroup());
            if (event != null) endEvent(event);
            return data;
        } catch (net.spy.memcached.internal.CheckedOperationTimeoutException ex) {
            status = EVCacheMetricsFactory.TIMEOUT;
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return null;
            throw new EVCacheException("CheckedOperationTimeoutException getting data for APP " + _appName + ", key = " + evcKey
                    + ".\nYou can set the following property to increase the timeout " + _appName
                    + ".EVCacheClientPool.readTimeout=<timeout in milli-seconds>", ex);
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return null;
            throw new EVCacheException("Exception getting data for APP " + _appName + ", key = " + evcKey, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.GET.name(), EVCacheMetricsFactory.READ, cacheOperation, status, tries, maxReadDuration.get().intValue(), client.getServerGroup()).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("GET : APP " + _appName + ", Took " + duration + " milliSec.");
        }
    }

    public EVCacheItemMetaData metaDebug(String key) throws EVCacheException {
        if (null == key) throw new IllegalArgumentException("Key cannot be null");
        final EVCacheKey evcKey = getEVCacheKey(key);
        final boolean throwExc = doThrowException();
        EVCacheClient client = _pool.getEVCacheClientForRead();
        if (client == null) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.META_DEBUG);
            if (throwExc) throw new EVCacheException("Could not find a client to get the metadata for APP " + _appName);
            return null; // Fast failure
        }

        final EVCacheEvent event = createEVCacheEvent(Collections.singletonList(client), Call.META_DEBUG);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.META_DEBUG);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + evcKey);
                    return null;
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.META_DEBUG);
                return null;
            }
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String status = EVCacheMetricsFactory.SUCCESS;
        String cacheOperation = EVCacheMetricsFactory.YES;
        int tries = 1;
        try {
            final boolean hasZF = hasZoneFallback();
            boolean throwEx = hasZF ? false : throwExc;
            EVCacheItemMetaData data = getEVCacheItemMetaData(client, evcKey, throwEx, hasZF);
            if (data == null && hasZF) {
                final List<EVCacheClient> fbClients = _pool.getEVCacheClientsForReadExcluding(client.getServerGroup());
                if (fbClients != null && !fbClients.isEmpty()) {
                    for (int i = 0; i < fbClients.size(); i++) {
                        final EVCacheClient fbClient = fbClients.get(i);
                        if(i >= fbClients.size() - 1) throwEx = throwExc;
                        if (event != null) {
                            try {
                                if (shouldThrottle(event)) {
                                    status = EVCacheMetricsFactory.THROTTLED;
                                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + evcKey);
                                    return null;
                                }
                            } catch(EVCacheException ex) {
                                if(throwExc) throw ex;
                                status = EVCacheMetricsFactory.THROTTLED;
                                return null;
                            }
                        }
                        tries++;
                        data = getEVCacheItemMetaData(fbClient, evcKey, throwEx, (i < fbClients.size() - 1) ? true : false);
                        if (log.isDebugEnabled() && shouldLog()) log.debug("Retry for APP " + _appName + ", key [" + evcKey + (log.isTraceEnabled() ? "], Value [" + data : "") + "], ServerGroup : " + fbClient.getServerGroup());
                        if (data != null) {
                            client = fbClient;
                            break;
                        }
                    }
                }
            }
            if (data != null) {
                if (event != null) event.setAttribute("status", "MDHIT");
            } else {
                cacheOperation = EVCacheMetricsFactory.NO;
                if (event != null) event.setAttribute("status", "MDMISS");
                if (log.isInfoEnabled() && shouldLog()) log.info("META_DEBUG : APP " + _appName + " ; cache miss for key : " + evcKey);
            }
            if (log.isDebugEnabled() && shouldLog()) log.debug("META_DEBUG : APP " + _appName + ", key [" + evcKey + (log.isTraceEnabled() ? "], Value [" + data : "") + "], ServerGroup : " + client.getServerGroup());
            if (event != null) endEvent(event);
            return data;
        } catch (net.spy.memcached.internal.CheckedOperationTimeoutException ex) {
            status = EVCacheMetricsFactory.TIMEOUT;
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return null;
            throw new EVCacheException("CheckedOperationTimeoutException getting with meta data for APP " + _appName + ", key = " + evcKey
                    + ".\nYou can set the following property to increase the timeout " + _appName
                    + ".EVCacheClientPool.readTimeout=<timeout in milli-seconds>", ex);
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return null;
            throw new EVCacheException("Exception getting with metadata for APP " + _appName + ", key = " + evcKey, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.META_DEBUG.name(), EVCacheMetricsFactory.READ, cacheOperation, status, tries, maxReadDuration.get().intValue(), client.getServerGroup()).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("META_DEBUG : APP " + _appName + ", Took " + duration + " milliSec.");
        }
    }

    public <T> EVCacheItem<T> metaGet(String key, Transcoder<T> tc) throws EVCacheException {
        if (null == key) throw new IllegalArgumentException("Key cannot be null");
        final EVCacheKey evcKey = getEVCacheKey(key);
        final boolean throwExc = doThrowException();
        EVCacheClient client = _pool.getEVCacheClientForRead();
        if (client == null) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.META_GET);
            if (throwExc) throw new EVCacheException("Could not find a client to get the data APP " + _appName);
            return null; // Fast failure
        }

        final EVCacheEvent event = createEVCacheEvent(Collections.singletonList(client), Call.META_GET);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.META_GET);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + evcKey);
                    return null;
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.META_GET);
                return null;
            }
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String status = EVCacheMetricsFactory.SUCCESS;
        String cacheOperation = EVCacheMetricsFactory.YES;
        int tries = 1;
        try {
            final boolean hasZF = hasZoneFallback();
            boolean throwEx = hasZF ? false : throwExc;
            EVCacheItem<T> data = getEVCacheItem(client, evcKey, tc, throwEx, hasZF);
            if (data == null && hasZF) {
                final List<EVCacheClient> fbClients = _pool.getEVCacheClientsForReadExcluding(client.getServerGroup());
                if (fbClients != null && !fbClients.isEmpty()) {
                    for (int i = 0; i < fbClients.size(); i++) {
                        final EVCacheClient fbClient = fbClients.get(i);
                        if(i >= fbClients.size() - 1) throwEx = throwExc;
                        if (event != null) {
                            try {
                                if (shouldThrottle(event)) {
                                    status = EVCacheMetricsFactory.THROTTLED;
                                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + evcKey);
                                    return null;
                                }
                            } catch(EVCacheException ex) {
                                if(throwExc) throw ex;
                                status = EVCacheMetricsFactory.THROTTLED;
                                return null;
                            }
                        }
                        tries++;
                        data = getEVCacheItem(fbClient, evcKey, tc, throwEx, (i < fbClients.size() - 1) ? true : false);
                        if (log.isDebugEnabled() && shouldLog()) log.debug("Retry for APP " + _appName + ", key [" + evcKey + (log.isTraceEnabled() ? "], Value [" + data : "") + "], ServerGroup : " + fbClient.getServerGroup());
                        if (data != null) {
                            client = fbClient;
                            break;
                        }
                    }
                }
            }
            if (data != null) {
                if (event != null) event.setAttribute("status", "MGHIT");
            } else {
                cacheOperation = EVCacheMetricsFactory.NO;
                if (event != null) event.setAttribute("status", "MGMISS");
                if (log.isInfoEnabled() && shouldLog()) log.info("META_GET : APP " + _appName + " ; cache miss for key : " + evcKey);
            }
            if (log.isDebugEnabled() && shouldLog()) log.debug("META_GET : APP " + _appName + ", key [" + evcKey + (log.isTraceEnabled() ? "], Value [" + data : "") + "], ServerGroup : " + client.getServerGroup());
            if (event != null) endEvent(event);
            return data;
        } catch (net.spy.memcached.internal.CheckedOperationTimeoutException ex) {
            status = EVCacheMetricsFactory.TIMEOUT;
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return null;
            throw new EVCacheException("CheckedOperationTimeoutException getting with meta data for APP " + _appName + ", key = " + evcKey
                    + ".\nYou can set the following property to increase the timeout " + _appName
                    + ".EVCacheClientPool.readTimeout=<timeout in milli-seconds>", ex);
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return null;
            throw new EVCacheException("Exception getting with meta data for APP " + _appName + ", key = " + evcKey, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.META_GET.name(), EVCacheMetricsFactory.READ, cacheOperation, status, tries, maxReadDuration.get().intValue(), client.getServerGroup()).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("META_GET : APP " + _appName + ", Took " + duration + " milliSec.");
        }
    }


    
    private int policyToCount(Policy policy, int count) {
        if (policy == null) return 0;
        switch (policy) {
        case NONE:
            return 0;
        case ONE:
            return 1;
        case QUORUM:
            if (count == 0)
                return 0;
            else if (count <= 2)
                return count;
            else
                return (count / 2) + 1;
        case ALL_MINUS_1:
            if (count == 0)
                return 0;
            else if (count <= 2)
                return 1;
            else
                return count - 1;
        default:
            return count;
        }
    }

    public <T> T get(String key, Transcoder<T> tc, Policy policy) throws EVCacheException {
        if (null == key) throw new IllegalArgumentException();

        final boolean throwExc = doThrowException();
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        if (clients.length == 0) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.GET);
            if (throwExc) throw new EVCacheException("Could not find a client to asynchronously get the data");
            return null; // Fast failure
        }

        final int expectedSuccessCount = policyToCount(policy, clients.length);
        if(expectedSuccessCount <= 1) return get(key, tc);

        final long startTime = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String status = EVCacheMetricsFactory.SUCCESS;
        String cacheOperation = EVCacheMetricsFactory.YES;
        int tries = 1;
        try {
            final List<Future<T>> futureList = new ArrayList<Future<T>>(clients.length);
            final long endTime = startTime + _pool.getReadTimeout().get().intValue();
            for (EVCacheClient client : clients) {
                final Future<T> future = getGetFuture(client, key, tc, throwExc);
                futureList.add(future);
                if (log.isDebugEnabled() && shouldLog()) log.debug("GET : CONSISTENT : APP " + _appName + ", Future " + future + " for key : " + key + " with policy : " + policy + " for client : " + client);
            }

            final Map<T, List<EVCacheClient>> evcacheClientMap = new HashMap<T, List<EVCacheClient>>();
            //final Map<T, Integer> tMap = new HashMap<T,Integer>();
            if (log.isDebugEnabled() && shouldLog()) log.debug("GET : CONSISTENT : Total Requests " + clients.length + "; Expected Success Count : " + expectedSuccessCount);
            for(Future<T> future : futureList) {
                try {
                    if(future instanceof EVCacheOperationFuture) {
                        EVCacheOperationFuture<T> evcacheOperationFuture = (EVCacheOperationFuture<T>)future;
                        long duration = endTime - System.currentTimeMillis();
                        if(duration < 20) duration = 20;
                        if (log.isDebugEnabled() && shouldLog()) log.debug("GET : CONSISTENT : block duration : " + duration);
                        final T t = evcacheOperationFuture.get(duration, TimeUnit.MILLISECONDS, throwExc, false);
                        if (log.isTraceEnabled() && shouldLog()) log.trace("GET : CONSISTENT : value : " + t);
                        if(t != null) {
                            final List<EVCacheClient> cList = evcacheClientMap.computeIfAbsent(t, k -> new ArrayList<EVCacheClient>(clients.length));
                            cList.add(evcacheOperationFuture.getEVCacheClient());
                            if (log.isDebugEnabled() && shouldLog()) log.debug("GET : CONSISTENT : Added Client to ArrayList " + cList);
                        }
                    }
                } catch (Exception e) {
                    log.error("Exception",e);
                }
            }
            T retVal = null;
             /* TODO : use metaget to get TTL and set it. For now we will delete the inconsistent value */
            for(Entry<T, List<EVCacheClient>> entry : evcacheClientMap.entrySet()) {
                if (log.isDebugEnabled() && shouldLog()) log.debug("GET : CONSISTENT : Existing Count for Value : " + entry.getValue().size() + "; expectedSuccessCount : " + expectedSuccessCount);
                if(entry.getValue().size() >= expectedSuccessCount) {
                    retVal = entry.getKey();
                } else {
                    for(EVCacheClient client : entry.getValue()) {
                        if (log.isDebugEnabled() && shouldLog()) log.debug("GET : CONSISTENT : Delete in-consistent vale from : " + client);
                        client.delete(key);
                    }
                }
            }
            if(retVal != null) {
                if (log.isDebugEnabled() && shouldLog()) log.debug("GET : CONSISTENT : policy : " + policy + " was met. Will return the value. Total Duration : " + (System.currentTimeMillis() - startTime) + " milli Seconds.");
                return retVal;
            }
            if (log.isDebugEnabled() && shouldLog()) log.debug("GET : CONSISTENT : policy : " + policy + " was NOT met. Will return NULL. Total Duration : " + (System.currentTimeMillis() - startTime) + " milli Seconds.");
            return null;
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (!throwExc) return null;
            throw new EVCacheException("Exception getting data for APP " + _appName + ", key = " + key, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- startTime;
            getTimer(Call.GET_ALL.name(), EVCacheMetricsFactory.READ, cacheOperation, status, tries, maxReadDuration.get().intValue(), null).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("GET : CONSISTENT : APP " + _appName + ", Took " + duration + " milliSec.");
        }

    }

    public <T> Single<T> get(String key, Scheduler scheduler) {
        return this.get(key, (Transcoder<T>) _transcoder, scheduler);
    }

    public <T> Single<T> get(String key, Transcoder<T> tc, Scheduler scheduler) {
        if (null == key) return Single.error(new IllegalArgumentException("Key cannot be null"));

        final boolean throwExc = doThrowException();
        final EVCacheClient client = _pool.getEVCacheClientForRead();
        if (client == null) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.GET);
            return Single.error(new EVCacheException("Could not find a client to get the data APP " + _appName));
        }

        final EVCacheKey evcKey = getEVCacheKey(key);
        final EVCacheEvent event = createEVCacheEvent(Collections.singletonList(client), Call.GET);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.GET);
                    return Single.error(new EVCacheException("Request Throttled for app " + _appName + " & key " + key));
                }
            } catch(EVCacheException ex) {
                throw sneakyThrow(ex);
            }
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        final boolean hasZF = hasZoneFallback();
        final boolean throwEx = hasZF ? false : throwExc;
        return getData(client, evcKey, tc, throwEx, hasZF, scheduler).flatMap(data -> {
            if (data == null && hasZF) {
                final List<EVCacheClient> fbClients = _pool.getEVCacheClientsForReadExcluding(client.getServerGroup());
                if (fbClients != null && !fbClients.isEmpty()) {
                    return Observable.concat(Observable.from(fbClients).map(
                            fbClient -> getData(fbClients.indexOf(fbClient), fbClients.size(), fbClient, evcKey, tc, throwEx, throwExc, false, scheduler) //TODO : for the last one make sure to pass throwExc
                            //.doOnSuccess(fbData -> increment("RETRY_" + ((fbData == null) ? "MISS" : "HIT")))
                            .toObservable()))
                            .firstOrDefault(null, fbData -> (fbData != null)).toSingle();
                }
            }
            return Single.just(data);
        }).map(data -> {
            //increment("GetCall");
            if (data != null) {
                //increment("GetHit");
                if (event != null) event.setAttribute("status", "GHIT");
            } else {
                //increment("GetMiss");
                if (event != null) event.setAttribute("status", "GMISS");
                if (log.isInfoEnabled() && shouldLog()) log.info("GET : APP " + _appName + " ; cache miss for key : " + evcKey);
            }
            if (log.isDebugEnabled() && shouldLog()) log.debug("GET : APP " + _appName + ", key [" + evcKey + (log.isTraceEnabled() ? "], Value [" + data : "")  + "], ServerGroup : " + client .getServerGroup());
            if (event != null) endEvent(event);
            return data;
        }).onErrorReturn(ex -> {
            if (ex instanceof net.spy.memcached.internal.CheckedOperationTimeoutException) {
                if (event != null) {
                    event.setStatus(EVCacheMetricsFactory.TIMEOUT);
                    eventError(event, ex);
                }
                if (!throwExc) return null;
                throw sneakyThrow(new EVCacheException("CheckedOperationTimeoutException getting data for APP " + _appName + ", key = "
                        + evcKey
                        + ".\nYou can set the following property to increase the timeout " + _appName
                        + ".EVCacheClientPool.readTimeout=<timeout in milli-seconds>", ex));
            } else {
                if (event != null) {
                    event.setStatus(EVCacheMetricsFactory.ERROR);
                    eventError(event, ex);
                }
                if (!throwExc) return null;
                throw sneakyThrow(new EVCacheException("Exception getting data for APP " + _appName + ", key = " + evcKey, ex));
            }
        }).doAfterTerminate(() -> {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.GET_AND_TOUCH.name(), EVCacheMetricsFactory.READ, null, EVCacheMetricsFactory.SUCCESS, 1, maxReadDuration.get().intValue(), client.getServerGroup()).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("GET : APP " + _appName + ", Took " + duration + " milliSec.");
        });
    }

    private <T> T getData(EVCacheClient client, EVCacheKey evcKey, Transcoder<T> tc, boolean throwException, boolean hasZF) throws Exception {
        if (client == null) return null;
        final Transcoder<T> transcoder = (tc == null) ? ((_transcoder == null) ? (Transcoder<T>) client.getTranscoder() : (Transcoder<T>) _transcoder) : tc;
        try {
            String hashKey = evcKey.getHashKey(client.isDuetClient());
            String canonicalKey = evcKey.getCanonicalKey(client.isDuetClient());

            if(hashKey != null) {
                final Object obj = client.get(hashKey, evcacheValueTranscoder, throwException, hasZF);
                if(obj != null && obj instanceof EVCacheValue) {
                    final EVCacheValue val = (EVCacheValue)obj;
                    if(!val.getKey().equals(canonicalKey)) {
                        incrementFailure(EVCacheMetricsFactory.KEY_HASH_COLLISION, Call.GET.name(), EVCacheMetricsFactory.READ);
                        return null;
                    }
                    final CachedData cd = new CachedData(val.getFlags(), val.getValue(), CachedData.MAX_SIZE);
                    return transcoder.decode(cd);
                } else {
                    return null;
                }
            } else {
                return client.get(canonicalKey, transcoder, throwException, hasZF);
            }
        } catch (EVCacheConnectException ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheConnectException while getting data for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        } catch (EVCacheReadQueueException ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheReadQueueException while getting data for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        } catch (EVCacheException ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheException while getting data for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        } catch (Exception ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception while getting data for APP " + _appName + ", key : " + evcKey, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        }
    }

    private EVCacheItemMetaData getEVCacheItemMetaData(EVCacheClient client, EVCacheKey evcKey, boolean throwException, boolean hasZF) throws Exception {
        if (client == null) return null;
        try {
            return client.metaDebug(evcKey.getDerivedKey(client.isDuetClient()));
        } catch (EVCacheConnectException ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheConnectException while getting with metadata for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        } catch (EVCacheReadQueueException ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheReadQueueException while getting with metadata for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        } catch (EVCacheException ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheException while getting with metadata for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        } catch (Exception ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception while getting with metadata for APP " + _appName + ", key : " + evcKey, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        }
    }

    private <T> EVCacheItem<T> getEVCacheItem(EVCacheClient client, EVCacheKey evcKey, Transcoder<T> tc, boolean throwException, boolean hasZF) throws Exception {
        if (client == null) return null;
        final Transcoder<T> transcoder = (tc == null) ? ((_transcoder == null) ? (Transcoder<T>) client.getTranscoder() : (Transcoder<T>) _transcoder) : tc;
        try {
            return client.metaGet(evcKey.getDerivedKey(client.isDuetClient()), transcoder, throwException, hasZF);
        } catch (EVCacheConnectException ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheConnectException while getting with meta data for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        } catch (EVCacheReadQueueException ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheReadQueueException while getting with meta data for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        } catch (EVCacheException ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheException while getting with meta data for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        } catch (Exception ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception while getting with meta data for APP " + _appName + ", key : " + evcKey, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        }
    }

    private <T> Single<T> getData(int index, int size, EVCacheClient client, EVCacheKey canonicalKey, Transcoder<T> tc, boolean throwEx, boolean throwExc, boolean hasZF, Scheduler scheduler) {
        if(index >= size -1) throwEx = throwExc;
        return getData(client, canonicalKey, tc, throwEx, hasZF, scheduler);
    }

    private <T> Single<T> getData(EVCacheClient client, EVCacheKey evcKey, Transcoder<T> tc, boolean throwException, boolean hasZF, Scheduler scheduler) {
        if (client == null) return Single.error(new IllegalArgumentException("Client cannot be null"));
        if(hashKey.get()) {
            return Single.error(new IllegalArgumentException("Not supported"));
        } else {
            final Transcoder<T> transcoder = (tc == null) ? ((_transcoder == null) ? (Transcoder<T>) client.getTranscoder() : (Transcoder<T>) _transcoder) : tc;
            return client.get(evcKey.getCanonicalKey(client.isDuetClient()), transcoder, throwException, hasZF, scheduler).onErrorReturn(ex -> {
                if (ex instanceof EVCacheReadQueueException) {
                    if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheReadQueueException while getting data for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
                    if (!throwException || hasZF) return null;
                    throw sneakyThrow(ex);
                } else if (ex instanceof EVCacheException) {
                    if (log.isDebugEnabled() && shouldLog()) log.debug("EVCacheException while getting data for APP " + _appName + ", key : " + evcKey + "; hasZF : " + hasZF, ex);
                    if (!throwException || hasZF) return null;
                    throw sneakyThrow(ex);
                } else {
                    if (log.isDebugEnabled() && shouldLog()) log.debug("Exception while getting data for APP " + _appName + ", key : " + evcKey, ex);
                    if (!throwException || hasZF) return null;
                    throw sneakyThrow(ex);
                }
            });
        }
    }

    private final int MAX_IN_SEC = 2592000;
    private void checkTTL(int timeToLive, Call call) throws IllegalArgumentException {
        try {
            if(timeToLive < 0) throw new IllegalArgumentException ("Time to Live ( " + timeToLive + ") must be great than or equal to 0.");
            final long currentTimeInMillis = System.currentTimeMillis();
            if(timeToLive > currentTimeInMillis) throw new IllegalArgumentException ("Time to Live ( " + timeToLive + ") must be in seconds.");
            if(timeToLive > MAX_IN_SEC && timeToLive < currentTimeInMillis/1000) throw new IllegalArgumentException ("If providing Time to Live ( " + timeToLive + ") in seconds as epoc value, it should be greater than current time " + currentTimeInMillis/1000);
        } catch (IllegalArgumentException iae) {
            incrementFastFail(EVCacheMetricsFactory.INVALID_TTL, call);
            throw iae;
        }
    }

    public <T> T getAndTouch(String key, int timeToLive) throws EVCacheException {
        return this.getAndTouch(key, timeToLive, (Transcoder<T>) _transcoder);
    }


    public <T> Single<T> getAndTouch(String key, int timeToLive, Scheduler scheduler) {
        return this.getAndTouch(key, timeToLive, (Transcoder<T>) _transcoder, scheduler);
    }

    public <T> Single<T> getAndTouch(String key, int timeToLive, Transcoder<T> tc, Scheduler scheduler) {
        if (null == key) return Single.error(new IllegalArgumentException("Key cannot be null"));
        checkTTL(timeToLive, Call.GET_AND_TOUCH);
        if(hashKey.get()) {
            return Single.error(new IllegalArgumentException("Not supported"));
        }

        final boolean throwExc = doThrowException();
        final EVCacheClient client = _pool.getEVCacheClientForRead();
        if (client == null) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.GET_AND_TOUCH);
            return Single.error(new EVCacheException("Could not find a client to get and touch the data for APP " + _appName));
        }

        final EVCacheKey evcKey = getEVCacheKey(key);
        final EVCacheEvent event = createEVCacheEvent(Collections.singletonList(client), Call.GET_AND_TOUCH);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.GET_AND_TOUCH);
                    return Single.error(new EVCacheException("Request Throttled for app " + _appName + " & key " + key));
                }
            } catch(EVCacheException ex) {
                throw sneakyThrow(ex);
            }
            event.setTTL(timeToLive);
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        final boolean hasZF = hasZoneFallback();
        final boolean throwEx = hasZF ? false : throwExc;
        //anyway we have to touch all copies so let's just reuse getData instead of getAndTouch
        return getData(client, evcKey, tc, throwEx, hasZF, scheduler).flatMap(data -> {
            if (data == null && hasZF) {
                final List<EVCacheClient> fbClients = _pool.getEVCacheClientsForReadExcluding(client.getServerGroup());
                if (fbClients != null && !fbClients.isEmpty()) {
                    return Observable.concat(Observable.from(fbClients).map(
                            //TODO : for the last one make sure to pass throwExc
                            fbClient -> getData(fbClients.indexOf(fbClient), fbClients.size(), fbClient, evcKey, tc, throwEx, throwExc, false, scheduler)
                            .doOnSuccess(fbData ->  {
                                //increment("RETRY_" + ((fbData == null) ? "MISS" : "HIT"));
                            })
                            .toObservable()))
                            .firstOrDefault(null, fbData -> (fbData != null)).toSingle();
                }
            }
            return Single.just(data);
        }).map(data -> {
            //increment("GetCall");
            if (data != null) {
                //increment("GetHit");
                if (event != null) event.setAttribute("status", "THIT");
                // touch all copies
                try {
                    touchData(evcKey, timeToLive);
                } catch (Exception e) {
                    throw sneakyThrow(new EVCacheException("Exception performing touch for APP " + _appName + ", key = " + evcKey, e));
                }
                if (log.isDebugEnabled() && shouldLog()) log.debug("GET_AND_TOUCH : APP " + _appName + ", key [" + evcKey + (log.isTraceEnabled() ? "], Value [" + data : "") + "], ServerGroup : " + client .getServerGroup());
            } else {
                //increment("GetMiss");
                if (event != null) event.setAttribute("status", "TMISS");
                if (log.isInfoEnabled() && shouldLog()) log.info("GET_AND_TOUCH : APP " + _appName + " ; cache miss for key : " + evcKey);
            }
            if (event != null) endEvent(event);
            return data;
        }).onErrorReturn(ex -> {
            if (ex instanceof net.spy.memcached.internal.CheckedOperationTimeoutException) {
                if (event != null) {
                    event.setStatus(EVCacheMetricsFactory.TIMEOUT);
                    eventError(event, ex);
                }
                if (!throwExc) return null;
                throw sneakyThrow(new EVCacheException("CheckedOperationTimeoutException executing getAndTouch APP " + _appName + ", key = " + evcKey
                        + ".\nYou can set the following property to increase the timeout " + _appName + ".EVCacheClientPool.readTimeout=<timeout in milli-seconds>", ex));
            } else {
                if (event != null) {
                    event.setStatus(EVCacheMetricsFactory.ERROR);
                    eventError(event, ex);
                }
                if (event != null) eventError(event, ex);
                if (!throwExc) return null;
                throw sneakyThrow(new EVCacheException("Exception executing getAndTouch APP " + _appName + ", key = " + evcKey, ex));
            }
        }).doAfterTerminate(() -> {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.GET_AND_TOUCH.name(), EVCacheMetricsFactory.READ, null, EVCacheMetricsFactory.SUCCESS, 1, maxReadDuration.get().intValue(),client.getServerGroup()).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("GET_AND_TOUCH : APP " + _appName + ", Took " + duration+ " milliSec.");
        });
    }

    @Override
    public <T> T getAndTouch(String key, int timeToLive, Transcoder<T> tc) throws EVCacheException {
        if (null == key) throw new IllegalArgumentException("Key cannot be null");
        checkTTL(timeToLive, Call.GET_AND_TOUCH);
        final EVCacheKey evcKey = getEVCacheKey(key);
        if (_useInMemoryCache.get()) {
            final boolean throwExc = doThrowException();
            T value = null;
            try {
                final Transcoder<T> transcoder = (tc == null) ? ((_transcoder == null) ? (Transcoder<T>) _pool.getEVCacheClientForRead().getTranscoder() : (Transcoder<T>) _transcoder) : tc;
                value = (T) getInMemoryCache(transcoder).get(evcKey);
            } catch (ExecutionException e) {
                if(throwExc) {
                    if(e.getCause() instanceof DataNotFoundException) {
                        return null;
                    }
                    if(e.getCause() instanceof EVCacheException) {
                        if (log.isDebugEnabled() && shouldLog()) log.debug("ExecutionException while getting data from InMemory Cache", e);
                        throw (EVCacheException)e.getCause();
                    }
                    throw new EVCacheException("ExecutionException", e);
                }
            }
            if (value != null) {
                try {
                    touchData(evcKey, timeToLive);
                } catch (Exception e) {
                    if (throwExc) throw new EVCacheException("Exception executing getAndTouch APP " + _appName + ", key = " + evcKey, e);
                }
                return value;
            }
        }
        if(ignoreTouch.get()) {
            return doGet(evcKey, tc);
        } else {
            return doGetAndTouch(evcKey, timeToLive, tc);
        }
    }

    <T> T doGetAndTouch(EVCacheKey evcKey, int timeToLive, Transcoder<T> tc) throws EVCacheException {
        final boolean throwExc = doThrowException();
        EVCacheClient client = _pool.getEVCacheClientForRead();
        if (client == null) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.GET_AND_TOUCH);
            if (throwExc) throw new EVCacheException("Could not find a client to get and touch the data for App " + _appName);
            return null; // Fast failure
        }

        final EVCacheEvent event = createEVCacheEvent(Collections.singletonList(client), Call.GET_AND_TOUCH);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.GET_AND_TOUCH);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + evcKey);
                    return null;
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.GET_AND_TOUCH);
                return null;
            }
            event.setTTL(timeToLive);
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String cacheOperation = EVCacheMetricsFactory.YES;
        int tries = 1;
        String status = EVCacheMetricsFactory.SUCCESS;
        try {
            final boolean hasZF = hasZoneFallback();
            boolean throwEx = hasZF ? false : throwExc;
            T data = getData(client, evcKey, tc, throwEx, hasZF);
            if (data == null && hasZF) {
                final List<EVCacheClient> fbClients = _pool.getEVCacheClientsForReadExcluding(client.getServerGroup());
                for (int i = 0; i < fbClients.size(); i++) {
                    final EVCacheClient fbClient = fbClients.get(i);
                    if(i >= fbClients.size() - 1) throwEx = throwExc;
                    if (event != null) {
                        try {
                            if (shouldThrottle(event)) {
                                status = EVCacheMetricsFactory.THROTTLED;
                                if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + evcKey);
                                return null;
                            }
                        } catch(EVCacheException ex) {
                            if(throwExc) throw ex;
                            status = EVCacheMetricsFactory.THROTTLED;
                            return null;
                        }
                    }
                    tries++;
                    data = getData(fbClient, evcKey, tc, throwEx, (i < fbClients.size() - 1) ? true : false);
                    if (log.isDebugEnabled() && shouldLog()) log.debug("GetAndTouch Retry for APP " + _appName + ", key [" + evcKey + (log.isTraceEnabled() ? "], Value [" + data : "")  + "], ServerGroup : " + fbClient.getServerGroup());
                    if (data != null) {
                        client = fbClient;
                        break;
                    }
                }
            }

            if (data != null) {
                if (event != null) event.setAttribute("status", "THIT");

                // touch all copies
                touchData(evcKey, timeToLive);
                if (log.isDebugEnabled() && shouldLog()) log.debug("GET_AND_TOUCH : APP " + _appName + ", key [" + evcKey + (log.isTraceEnabled() ? "], Value [" + data : "") + "], ServerGroup : " + client.getServerGroup());
            } else {
                cacheOperation = EVCacheMetricsFactory.NO;
                if (log.isInfoEnabled() && shouldLog()) log.info("GET_AND_TOUCH : APP " + _appName + " ; cache miss for key : " + evcKey);
                if (event != null) event.setAttribute("status", "TMISS");
            }
            if (event != null) endEvent(event);
            return data;
        } catch (net.spy.memcached.internal.CheckedOperationTimeoutException ex) {
            status = EVCacheMetricsFactory.TIMEOUT;
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (log.isDebugEnabled() && shouldLog()) log.debug("CheckedOperationTimeoutException executing getAndTouch APP " + _appName + ", key : " + evcKey, ex);
            if (!throwExc) return null;
            throw new EVCacheException("CheckedOperationTimeoutException executing getAndTouch APP " + _appName + ", key  = " + evcKey
                    + ".\nYou can set the following property to increase the timeout " + _appName+ ".EVCacheClientPool.readTimeout=<timeout in milli-seconds>", ex);
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception executing getAndTouch APP " + _appName + ", key = " + evcKey, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return null;
            throw new EVCacheException("Exception executing getAndTouch APP " + _appName + ", key = " + evcKey, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.GET_AND_TOUCH.name(), EVCacheMetricsFactory.READ, cacheOperation, status, tries, maxReadDuration.get().intValue(), client.getServerGroup()).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("Took " + duration + " milliSec to get&Touch the value for APP " + _appName + ", key " + evcKey);
        }
    }

    @Override
    public Future<Boolean>[] touch(String key, int timeToLive) throws EVCacheException {
        checkTTL(timeToLive, Call.TOUCH);
        final EVCacheLatch latch = this.touch(key, timeToLive, null);
        if (latch == null) return new EVCacheFuture[0];
        final List<Future<Boolean>> futures = latch.getAllFutures();
        if (futures == null || futures.isEmpty()) return new EVCacheFuture[0];
        final EVCacheFuture[] eFutures = new EVCacheFuture[futures.size()];
        for (int i = 0; i < futures.size(); i++) {
            final Future<Boolean> future = futures.get(i);
            if (future instanceof EVCacheFuture) {
                eFutures[i] = (EVCacheFuture) future;
            } else if (future instanceof EVCacheOperationFuture) {
                final EVCacheOperationFuture<Boolean> evfuture = (EVCacheOperationFuture<Boolean>)future;
                eFutures[i] = new EVCacheFuture(future, key, _appName, evfuture.getServerGroup(), evfuture.getEVCacheClient());
            } else {
                eFutures[i] = new EVCacheFuture(future, key, _appName, null);
            }
        }
        return eFutures;
    }

    public <T> EVCacheLatch touch(String key, int timeToLive, Policy policy) throws EVCacheException {
        if (null == key) throw new IllegalArgumentException();
        checkTTL(timeToLive, Call.TOUCH);

        final boolean throwExc = doThrowException();
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        if (clients.length == 0) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.TOUCH);
            if (throwExc) throw new EVCacheException("Could not find a client to set the data");
            return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
        }

        final EVCacheKey evcKey = getEVCacheKey(key);
        final EVCacheEvent event = createEVCacheEvent(Arrays.asList(clients), Call.TOUCH);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.TOUCH);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + key);
                    return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.TOUCH);
                return null;
            }
            startEvent(event);
        }

        String status = EVCacheMetricsFactory.SUCCESS;
        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        try {
            final EVCacheLatchImpl latch = new EVCacheLatchImpl(policy == null ? Policy.ALL_MINUS_1 : policy, clients.length - _pool.getWriteOnlyEVCacheClients().length, _appName);
            touchData(evcKey, timeToLive, clients, latch);

            if (event != null) {
                event.setTTL(timeToLive);
                if(_eventsUsingLatchFP.get()) {
                    latch.setEVCacheEvent(event);
                    latch.scheduledFutureValidation();
                } else {
                    endEvent(event);
                }
            }
            return latch;
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception touching the data for APP " + _appName + ", key : " + evcKey, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return new EVCacheLatchImpl(policy, 0, _appName);
            throw new EVCacheException("Exception setting data for APP " + _appName + ", key : " + evcKey, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTTLDistributionSummary(Call.TOUCH.name(), EVCacheMetricsFactory.WRITE, EVCacheMetricsFactory.TTL).record(timeToLive);
            getTimer(Call.TOUCH.name(), EVCacheMetricsFactory.WRITE, null, status, 1, maxWriteDuration.get().intValue(), null).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("TOUCH : APP " + _appName + " for key : " + evcKey + " with timeToLive : " + timeToLive);
        }
    }

    private void touchData(EVCacheKey evcKey, int timeToLive) throws Exception {
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        touchData(evcKey, timeToLive, clients);
    }

    private void touchData(EVCacheKey evcKey, int timeToLive, EVCacheClient[] clients) throws Exception {
        touchData(evcKey, timeToLive, clients, null);
    }


    private void touchData(EVCacheKey evcKey, int timeToLive, EVCacheClient[] clients, EVCacheLatch latch ) throws Exception {
        checkTTL(timeToLive, Call.TOUCH);
        for (EVCacheClient client : clients) {
            client.touch(evcKey.getDerivedKey(client.isDuetClient()), timeToLive, latch);
        }
    }

    public <T> Future<T> getAsynchronous(String key) throws EVCacheException {
        return this.getAsynchronous(key, (Transcoder<T>) _transcoder);
    };

    @Override
    public <T> Future<T> getAsynchronous(final String key, final Transcoder<T> tc) throws EVCacheException {
        if (null == key) throw new IllegalArgumentException("Key is null.");

        final boolean throwExc = doThrowException();
        final EVCacheClient client = _pool.getEVCacheClientForRead();
        if (client == null) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.ASYNC_GET);
            if (throwExc) throw new EVCacheException("Could not find a client to asynchronously get the data");
            return null; // Fast failure
        }

        return getGetFuture(client, key, tc, throwExc);
    }

    private <T> Future<T> getGetFuture(final EVCacheClient client, final String key, final Transcoder<T> tc, final boolean throwExc) throws EVCacheException {
        final EVCacheKey evcKey = getEVCacheKey(key);
        final EVCacheEvent event = createEVCacheEvent(Collections.singletonList(client), Call.ASYNC_GET);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.ASYNC_GET);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + key);
                    return null;
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.ASYNC_GET);
                return null;
            }
            startEvent(event);
        }
        String status = EVCacheMetricsFactory.SUCCESS;
        final Future<T> r;
        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        try {
            String hashKey = evcKey.getHashKey(client.isDuetClient());
            String canonicalKey = evcKey.getCanonicalKey(client.isDuetClient());
            if(hashKey != null) {
                final Future<Object> objFuture = client.asyncGet(hashKey, evcacheValueTranscoder, throwExc, false);
                r = new Future<T> () {

                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        return objFuture.cancel(mayInterruptIfRunning);
                    }

                    @Override
                    public boolean isCancelled() {
                        return objFuture.isCancelled();
                    }

                    @Override
                    public boolean isDone() {
                        return objFuture.isDone();
                    }

                    @Override
                    public T get() throws InterruptedException, ExecutionException {
                        return getFromObj(objFuture.get());
                    }

                    private T getFromObj(Object obj) {
                        if(obj != null && obj instanceof EVCacheValue) {
                            final EVCacheValue val = (EVCacheValue)obj;
                            if(!val.getKey().equals(canonicalKey)) {
                                incrementFailure(EVCacheMetricsFactory.KEY_HASH_COLLISION, Call.ASYNC_GET.name(), EVCacheMetricsFactory.READ);
                                return null;
                            }
                            final CachedData cd = new CachedData(val.getFlags(), val.getValue(), CachedData.MAX_SIZE);
                            final Transcoder<T> transcoder = (tc == null) ? ((_transcoder == null) ? (Transcoder<T>) client.getTranscoder() : (Transcoder<T>) _transcoder) : tc;
                            return transcoder.decode(cd);
                        } else {
                            return null;
                        }

                    }

                    @Override
                    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                        return getFromObj(objFuture.get(timeout, unit));
                    }
                };
            } else {
                final Transcoder<T> transcoder = (tc == null) ? ((_transcoder == null) ? (Transcoder<T>) client.getTranscoder() : (Transcoder<T>) _transcoder) : tc;
                r = client.asyncGet(canonicalKey, transcoder, throwExc, false);
            }
            if (event != null) endEvent(event);
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (log.isDebugEnabled() && shouldLog()) log.debug( "Exception while getting data for keys Asynchronously APP " + _appName + ", key : " + key, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return null;
            throw new EVCacheException("Exception getting data for APP " + _appName + ", key : " + key, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.ASYNC_GET.name(), EVCacheMetricsFactory.READ, null, status, 1, maxReadDuration.get().intValue(), client.getServerGroup()).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("Took " + duration + " milliSec to execute AsyncGet the value for APP " + _appName + ", key " + key);
        }

        return r;
    }

    private <T> Map<EVCacheKey, T> getBulkData(EVCacheClient client, Collection<EVCacheKey> evcacheKeys, Transcoder<T> tc, boolean throwException, boolean hasZF) throws Exception {
        try {
            boolean hasHashedKey = false;
            final Map<String, EVCacheKey> keyMap = new HashMap<String, EVCacheKey>(evcacheKeys.size() * 2);
            for(EVCacheKey evcKey : evcacheKeys) {
                String key = evcKey.getCanonicalKey(client.isDuetClient());
                String hashKey = evcKey.getHashKey(client.isDuetClient());
                if(hashKey != null) {
                    if (log.isDebugEnabled() && shouldLog()) log.debug("APP " + _appName + ", key [" + key + "], has been hashed [" + hashKey + "]");
                    key = hashKey;
                    hasHashedKey = true;
                }
                keyMap.put(key, evcKey);
            }
            if(hasHashedKey) {
                final Map<String, Object> objMap = client.getBulk(keyMap.keySet(), evcacheValueTranscoder, throwException, hasZF);
                final Map<EVCacheKey, T> retMap = new HashMap<EVCacheKey, T>((int)(objMap.size()/0.75) + 1);
                for (Map.Entry<String, Object> i : objMap.entrySet()) {
                    final Object obj = i.getValue();
                    if(obj instanceof EVCacheValue) {
                        if (log.isDebugEnabled() && shouldLog()) log.debug("APP " + _appName + ", The value for key [" + i.getKey() + "] is EVCache Value");
                        final EVCacheValue val = (EVCacheValue)obj;
                        final CachedData cd = new CachedData(val.getFlags(), val.getValue(), CachedData.MAX_SIZE);
                        final T tVal;
                        if(tc == null) {
                            tVal = (T)client.getTranscoder().decode(cd);
                        } else {
                            tVal = tc.decode(cd);
                        }
                        final EVCacheKey evcKey = keyMap.get(i.getKey());
                        if(evcKey.getCanonicalKey(client.isDuetClient()).equals(val.getKey())) {
                            if (log.isDebugEnabled() && shouldLog()) log.debug("APP " + _appName + ", key [" + i.getKey() + "] EVCacheKey " + evcKey);
                            retMap.put(evcKey, tVal);
                        	
                        } else {
                            if (log.isDebugEnabled() && shouldLog()) log.debug("CACHE COLLISION : APP " + _appName + ", key [" + i.getKey() + "] EVCacheKey " + evcKey);
                            incrementFailure(EVCacheMetricsFactory.KEY_HASH_COLLISION, Call.BULK.name(), EVCacheMetricsFactory.READ);
                        }
                    } else {
                        final EVCacheKey evcKey = keyMap.get(i.getKey());
                        if (log.isDebugEnabled() && shouldLog()) log.debug("APP " + _appName + ", key [" + i.getKey() + "] EVCacheKey " + evcKey);
                        retMap.put(evcKey, (T)obj);
                    }
                }
                return retMap;

            } else {
                if(tc == null && _transcoder != null) tc = (Transcoder<T>)_transcoder;
                final Map<String, T> objMap = client.getBulk(keyMap.keySet(), tc, throwException, hasZF);
                final Map<EVCacheKey, T> retMap = new HashMap<EVCacheKey, T>((int)(objMap.size()/0.75) + 1);
                for (Map.Entry<String, T> i : objMap.entrySet()) {
                    final EVCacheKey evcKey = keyMap.get(i.getKey());
                    if (log.isDebugEnabled() && shouldLog()) log.debug("APP " + _appName + ", key [" + i.getKey() + "] EVCacheKey " + evcKey);
                    retMap.put(evcKey, i.getValue());
                }
                return retMap;
            }
        } catch (Exception ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception while getBulk data for APP " + _appName + ", key : " + evcacheKeys, ex);
            if (!throwException || hasZF) return null;
            throw ex;
        }
    }

    public <T> Map<String, T> getBulk(Collection<String> keys, Transcoder<T> tc) throws EVCacheException {
        return getBulk(keys, tc, false, 0);
    }

    public <T> Map<String, T> getBulkAndTouch(Collection<String> keys, Transcoder<T> tc, int timeToLive)
            throws EVCacheException {
        return getBulk(keys, tc, true, timeToLive);
    }

    private <T> Map<String, T> getBulk(final Collection<String> keys, Transcoder<T> tc, boolean touch, int timeToLive) throws EVCacheException {
        if (null == keys) throw new IllegalArgumentException();
        if (keys.isEmpty()) return Collections.<String, T> emptyMap();
        checkTTL(timeToLive, Call.BULK);
        final boolean throwExc = doThrowException();
        final EVCacheClient client = _pool.getEVCacheClientForRead();
        if (client == null) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.BULK);
            if (throwExc) throw new EVCacheException("Could not find a client to get the data in bulk");
            return Collections.<String, T> emptyMap();// Fast failure
        }

        final Map<String, T> decanonicalR = new HashMap<String, T>((keys.size() * 4) / 3 + 1);
        final Collection<EVCacheKey> evcKeys = new ArrayList<EVCacheKey>();
        /* Canonicalize keys and perform fast failure checking */
        for (String k : keys) {
            final EVCacheKey evcKey = getEVCacheKey(k);
            T value = null;
            if (_useInMemoryCache.get()) {
                try {
                    final Transcoder<T> transcoder = (tc == null) ? ((_transcoder == null) ? (Transcoder<T>) _pool.getEVCacheClientForRead().getTranscoder() : (Transcoder<T>) _transcoder) : tc;
                    value = (T) getInMemoryCache(transcoder).get(evcKey);
                    if(value == null)  if (log.isInfoEnabled() && shouldLog()) log.info("Value not_found in inmemory cache for APP " + _appName + ", key : " + evcKey + "; value : " + value );
                } catch (ExecutionException e) {
                    if (log.isDebugEnabled() && shouldLog()) log.debug("ExecutionException while getting data from InMemory Cache", e);
                    throw new EVCacheException("ExecutionException", e);
                }
            }
            if(value == null) {
                evcKeys.add(evcKey);
            } else {
                decanonicalR.put(evcKey.getKey(), value);
                if (log.isDebugEnabled() && shouldLog()) log.debug("Value retrieved from inmemory cache for APP " + _appName + ", key : " + evcKey + (log.isTraceEnabled() ? "; value : " + value : ""));
            }
        }
        
        if(evcKeys.size() == 0 && decanonicalR.size() == keys.size()) {
        	if (log.isDebugEnabled() && shouldLog()) log.debug("All Values retrieved from inmemory cache for APP " + _appName + ", keys : " + keys + (log.isTraceEnabled() ? "; value : " + decanonicalR : ""));
        	return decanonicalR;
        }

        final EVCacheEvent event = createEVCacheEvent(Collections.singletonList(client), Call.BULK);
        if (event != null) {
            event.setEVCacheKeys(evcKeys);
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.BULK);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & keys " + keys);
                    return Collections.<String, T> emptyMap();
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.BULK);
                return null;
            }
            event.setTTL(timeToLive);
            startEvent(event);
        }


        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String cacheOperation = EVCacheMetricsFactory.YES;
        int tries = 1;
        String status = EVCacheMetricsFactory.SUCCESS;
        try {
            final boolean hasZF = hasZoneFallbackForBulk();
            boolean throwEx = hasZF ? false : throwExc;
            Map<EVCacheKey, T> retMap = getBulkData(client, evcKeys, tc, throwEx, hasZF);
            List<EVCacheClient> fbClients = null;
            if (hasZF) {
                if (retMap == null || retMap.isEmpty()) {
                    fbClients = _pool.getEVCacheClientsForReadExcluding(client.getServerGroup());
                    if (fbClients != null && !fbClients.isEmpty()) {
                        for (int i = 0; i < fbClients.size(); i++) {
                            final EVCacheClient fbClient = fbClients.get(i);
                            if(i >= fbClients.size() - 1) throwEx = throwExc;
                            if (event != null) {
                                try {
                                    if (shouldThrottle(event)) {
                                        status = EVCacheMetricsFactory.THROTTLED;
                                        if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + evcKeys);
                                        return null;
                                    }
                                } catch(EVCacheException ex) {
                                    if(throwExc) throw ex;
                                    status = EVCacheMetricsFactory.THROTTLED;
                                    return null;
                                }
                            }
                            tries++;
                            retMap = getBulkData(fbClient, evcKeys, tc, throwEx, (i < fbClients.size() - 1) ? true : false);
                            if (log.isDebugEnabled() && shouldLog()) log.debug("Fallback for APP " + _appName + ", key [" + evcKeys + (log.isTraceEnabled() ? "], Value [" + retMap : "") + "], zone : " + fbClient.getZone());
                            if (retMap != null && !retMap.isEmpty()) break;
                        }
                        //increment("BULK-FULL_RETRY-" + ((retMap == null || retMap.isEmpty()) ? "MISS" : "HIT"));
                    }
                } else if (retMap != null && keys.size() > retMap.size() && _bulkPartialZoneFallbackFP.get()) {
                    final int initRetrySize = keys.size() - retMap.size();
                    List<EVCacheKey> retryEVCacheKeys = new ArrayList<EVCacheKey>(initRetrySize);
                    for (Iterator<EVCacheKey> keysItr = evcKeys.iterator(); keysItr.hasNext();) {
                        final EVCacheKey key = keysItr.next();
                        if (!retMap.containsKey(key)) {
                            retryEVCacheKeys.add(key);
                        }
                    }

                    fbClients = _pool.getEVCacheClientsForReadExcluding(client.getServerGroup());
                    if (fbClients != null && !fbClients.isEmpty()) {
                        for (int ind = 0; ind < fbClients.size(); ind++) {
                            final EVCacheClient fbClient = fbClients.get(ind);
                            if (event != null) {
                                try {
                                    if (shouldThrottle(event)) {
                                        status = EVCacheMetricsFactory.THROTTLED;
                                        if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & keys " + retryEVCacheKeys);
                                        return null;
                                    }
                                } catch(EVCacheException ex) {
                                    status = EVCacheMetricsFactory.THROTTLED;
                                    if(throwExc) throw ex;
                                    return null;
                                }
                            }
                            tries++;

                            final Map<EVCacheKey, T> fbRetMap = getBulkData(fbClient, retryEVCacheKeys, tc, false, hasZF);
                            if (log.isDebugEnabled() && shouldLog()) log.debug("Fallback for APP " + _appName + ", key [" + retryEVCacheKeys + "], Fallback Server Group : " + fbClient .getServerGroup().getName());
                            for (Map.Entry<EVCacheKey, T> i : fbRetMap.entrySet()) {
                                retMap.put(i.getKey(), i.getValue());
                                if (log.isDebugEnabled() && shouldLog()) log.debug("Fallback for APP " + _appName + ", key [" + i.getKey() + (log.isTraceEnabled() ? "], Value [" + i.getValue(): "]"));
                            }
                            if (retryEVCacheKeys.size() == fbRetMap.size()) break;
                            if (ind < fbClients.size()) {
                                retryEVCacheKeys = new ArrayList<EVCacheKey>(keys.size() - retMap.size());
                                for (Iterator<EVCacheKey> keysItr = evcKeys.iterator(); keysItr.hasNext();) {
                                    final EVCacheKey key = keysItr.next();
                                    if (!retMap.containsKey(key)) {
                                        retryEVCacheKeys.add(key);
                                    }
                                }
                            }
                        }
                    }
                    if (log.isDebugEnabled() && shouldLog() && retMap.size() == keys.size()) log.debug("Fallback SUCCESS for APP " + _appName + ",  retMap [" + retMap + "]");
                }
            }

            if(decanonicalR.isEmpty()) {
                if (retMap == null || retMap.isEmpty()) {
                    if (log.isInfoEnabled() && shouldLog()) log.info("BULK : APP " + _appName + " ; Full cache miss for keys : " + keys);
                    if (event != null) event.setAttribute("status", "BMISS_ALL");
    
                    final Map<String, T> returnMap = new HashMap<String, T>();
                    if (retMap != null && retMap.isEmpty()) {
                        for (String k : keys) {
                            returnMap.put(k, null);
                        }
                    }
                    //increment("BulkMissFull");
                    cacheOperation = EVCacheMetricsFactory.NO;
                    /* If both Retry and first request fail Exit Immediately. */
                    if (event != null) endEvent(event);
                    return returnMap;
                }
            }

            /* Decanonicalize the keys */
            boolean partialHit = false;
            final List<String> decanonicalHitKeys = new ArrayList<String>(retMap.size());
            for (Iterator<EVCacheKey> itr = evcKeys.iterator(); itr.hasNext();) {
                final EVCacheKey key = itr.next();
                final String deCanKey = key.getKey();
                final T value = retMap.get(key);
                if (value != null) {
                    decanonicalR.put(deCanKey, value);
                    if (touch) touchData(key, timeToLive);
                    decanonicalHitKeys.add(deCanKey);
                } else {
                    partialHit = true;
                    // this ensures the fallback was tried
                    decanonicalR.put(deCanKey, null);
                }
            }
            if (!decanonicalR.isEmpty()) {
                if (!partialHit) {
                    if (event != null) event.setAttribute("status", "BHIT");
                } else {
                    if (event != null) {
                        event.setAttribute("status", "BHIT_PARTIAL");
                        event.setAttribute("BHIT_PARTIAL_KEYS", decanonicalHitKeys);
                    }
                    //increment("BulkHitPartial");
                    cacheOperation = EVCacheMetricsFactory.PARTIAL;
                    if (log.isInfoEnabled() && shouldLog()) log.info("BULK_HIT_PARTIAL for APP " + _appName + ", keys in cache [" + decanonicalR + "], all keys [" + keys + "]");
                }
            }

            if (log.isDebugEnabled() && shouldLog()) log.debug("APP " + _appName + ", BULK : Data [" + decanonicalR + "]");
            if (event != null) endEvent(event);
            return decanonicalR;
        } catch (net.spy.memcached.internal.CheckedOperationTimeoutException ex) {
            status = EVCacheMetricsFactory.TIMEOUT;
            if (log.isDebugEnabled() && shouldLog()) log.debug("CheckedOperationTimeoutException getting bulk data for APP " + _appName + ", keys : " + evcKeys, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return null;
            throw new EVCacheException("CheckedOperationTimeoutException getting bulk data for APP " + _appName + ", keys = " + evcKeys
                    + ".\nYou can set the following property to increase the timeout " + _appName + ".EVCacheClientPool.bulkReadTimeout=<timeout in milli-seconds>", ex);
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception getting bulk data for APP " + _appName + ", keys = " + evcKeys, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return null;
            throw new EVCacheException("Exception getting bulk data for APP " + _appName + ", keys = " + evcKeys, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;

            if(bulkKeysSize == null) {
                final List<Tag> tagList = new ArrayList<Tag>(4);
                tagList.addAll(tags);
                tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TAG, EVCacheMetricsFactory.BULK_OPERATION));
                tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TYPE_TAG, EVCacheMetricsFactory.READ));
//                if(status != null) tagList.add(new BasicTag(EVCacheMetricsFactory.STATUS, status));
//                if(tries >= 0) tagList.add(new BasicTag(EVCacheMetricsFactory.ATTEMPT, String.valueOf(tries)));
                bulkKeysSize = EVCacheMetricsFactory.getInstance().getDistributionSummary(EVCacheMetricsFactory.OVERALL_KEYS_SIZE, tagList);
            }
            bulkKeysSize.record(keys.size());
            getTimer(Call.BULK.name(), EVCacheMetricsFactory.READ, cacheOperation, status, tries, maxReadDuration.get().intValue(), client.getServerGroup()).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("BULK : APP " + _appName + " Took " + duration + " milliSec to get the value for key " + evcKeys);
        }
    }

    public <T> Map<String, T> getBulk(Collection<String> keys) throws EVCacheException {
        return (this.getBulk(keys, (Transcoder<T>) _transcoder));
    }

    public <T> Map<String, T> getBulk(String... keys) throws EVCacheException {
        return (this.getBulk(Arrays.asList(keys), (Transcoder<T>) _transcoder));
    }

    public <T> Map<String, T> getBulk(Transcoder<T> tc, String... keys) throws EVCacheException {
        return (this.getBulk(Arrays.asList(keys), tc));
    }

    @Override
    public <T> EVCacheFuture[] set(String key, T value, Transcoder<T> tc, int timeToLive) throws EVCacheException {
        final EVCacheLatch latch = this.set(key, value, tc, timeToLive, null);
        if (latch == null) return new EVCacheFuture[0];
        final List<Future<Boolean>> futures = latch.getAllFutures();
        if (futures == null || futures.isEmpty()) return new EVCacheFuture[0];
        final EVCacheFuture[] eFutures = new EVCacheFuture[futures.size()];
        for (int i = 0; i < futures.size(); i++) {
            final Future<Boolean> future = futures.get(i);
            if (future instanceof EVCacheFuture) {
                eFutures[i] = (EVCacheFuture) future;
            } else if (future instanceof EVCacheOperationFuture) {
                eFutures[i] = new EVCacheFuture(futures.get(i), key, _appName, ((EVCacheOperationFuture<T>) futures.get(i)).getServerGroup());
            } else {
                eFutures[i] = new EVCacheFuture(future, key, _appName, null);
            }
        }
        return eFutures;
    }

    public <T> EVCacheLatch set(String key, T value, Policy policy) throws EVCacheException {
        return set(key, value, (Transcoder<T>)_transcoder, _timeToLive, policy);
    }

    public <T> EVCacheLatch set(String key, T value, int timeToLive, Policy policy) throws EVCacheException {
        return set(key, value, (Transcoder<T>)_transcoder, timeToLive, policy);
    }

    public <T> EVCacheLatch set(String key, T value, Transcoder<T> tc, EVCacheLatch.Policy policy) throws EVCacheException {
        return set(key, value, tc, _timeToLive, policy);
    }

    public <T> EVCacheLatch set(String key, T value, Transcoder<T> tc, int timeToLive, Policy policy) throws EVCacheException {
        if ((null == key) || (null == value)) throw new IllegalArgumentException();
        checkTTL(timeToLive, Call.SET);

        final boolean throwExc = doThrowException();
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        if (clients.length == 0) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.SET);
            if (throwExc) throw new EVCacheException("Could not find a client to set the data");
            return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
        }

        final EVCacheKey evcKey = getEVCacheKey(key);
        final EVCacheEvent event = createEVCacheEvent(Arrays.asList(clients), Call.SET);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.SET);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + key);
                    return new EVCacheLatchImpl(policy, 0, _appName);
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.SET);
                return null;
            }
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String status = EVCacheMetricsFactory.SUCCESS;

        final EVCacheLatchImpl latch = new EVCacheLatchImpl(policy == null ? Policy.ALL_MINUS_1 : policy, clients.length - _pool.getWriteOnlyEVCacheClients().length, _appName);
        try {
            CachedData cd = null;
            for (EVCacheClient client : clients) {
                String canonicalKey = evcKey.getCanonicalKey(client.isDuetClient());
                String hashKey = evcKey.getHashKey(client.isDuetClient());
                if (cd == null) {
                    if (tc != null) {
                        cd = tc.encode(value);
                    } else if ( _transcoder != null) {
                        cd = ((Transcoder<Object>)_transcoder).encode(value);
                    } else {
                        cd = client.getTranscoder().encode(value);
                    }
                    if(hashKey != null) {
                        final EVCacheValue val = new EVCacheValue(canonicalKey, cd.getData(), cd.getFlags(), timeToLive, System.currentTimeMillis());
                        cd = evcacheValueTranscoder.encode(val);
                    }
                }
                final Future<Boolean> future = client.set(hashKey == null ? canonicalKey : hashKey, cd, timeToLive, latch);
                if (log.isDebugEnabled() && shouldLog()) log.debug("SET : APP " + _appName + ", Future " + future + " for key : " + evcKey);
            }
            if (event != null) {
                event.setTTL(timeToLive);
                event.setCachedData(cd);
                if(_eventsUsingLatchFP.get()) {
                    latch.setEVCacheEvent(event);
                    latch.scheduledFutureValidation();
                } else {
                    endEvent(event);
                }
            }
            return latch;
        } catch (Exception ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception setting the data for APP " + _appName + ", key : " + evcKey, ex);
            if (event != null) endEvent(event);
            status = EVCacheMetricsFactory.ERROR;
            if (!throwExc) return new EVCacheLatchImpl(policy, 0, _appName);
            throw new EVCacheException("Exception setting data for APP " + _appName + ", key : " + evcKey, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTTLDistributionSummary(Call.SET.name(), EVCacheMetricsFactory.WRITE, EVCacheMetricsFactory.TTL).record(timeToLive);
            getTimer(Call.SET.name(), EVCacheMetricsFactory.WRITE, null, status, 1, maxWriteDuration.get().intValue(), null).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("SET : APP " + _appName + ", Took " + duration + " milliSec for key : " + evcKey);
        }
    }

    public <T> EVCacheFuture[] append(String key, T value, int timeToLive) throws EVCacheException {
        return this.append(key, value, null, timeToLive);
    }

    public <T> EVCacheFuture[] append(String key, T value, Transcoder<T> tc, int timeToLive) throws EVCacheException {
        if ((null == key) || (null == value)) throw new IllegalArgumentException();
        checkTTL(timeToLive, Call.APPEND);

        final boolean throwExc = doThrowException();
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        if (clients.length == 0) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.APPEND);
            if (throwExc) throw new EVCacheException("Could not find a client to set the data");
            return new EVCacheFuture[0]; // Fast failure
        }

        final EVCacheEvent event = createEVCacheEvent(Arrays.asList(clients), Call.APPEND);
        final EVCacheKey evcKey = getEVCacheKey(key);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.APPEND);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + key);
                    return new EVCacheFuture[0];
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.APPEND);
                return null;
            }
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String status = EVCacheMetricsFactory.SUCCESS;
        try {
            final EVCacheFuture[] futures = new EVCacheFuture[clients.length];
            CachedData cd = null;
            int index = 0;
            for (EVCacheClient client : clients) {
                if (cd == null) {
                    if (tc != null) {
                        cd = tc.encode(value);
                    } else if ( _transcoder != null) {
                        cd = ((Transcoder<Object>)_transcoder).encode(value);
                    } else {
                        cd = client.getTranscoder().encode(value);
                    }
                    //if (cd != null) EVCacheMetricsFactory.getInstance().getDistributionSummary(_appName + "-AppendData-Size", tags).record(cd.getData().length);
                }
                final Future<Boolean> future = client.append(evcKey.getDerivedKey(client.isDuetClient()), cd);
                futures[index++] = new EVCacheFuture(future, key, _appName, client.getServerGroup());
            }
            if (event != null) {
                event.setCachedData(cd);
                event.setTTL(timeToLive);
                endEvent(event);
            }
            touchData(evcKey, timeToLive, clients);
            return futures;
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception setting the data for APP " + _appName + ", key : " + evcKey, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return new EVCacheFuture[0];
            throw new EVCacheException("Exception setting data for APP " + _appName + ", key : " + evcKey, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            //timer.record(duration, TimeUnit.MILLISECONDS);
            getTimer(Call.APPEND.name(), EVCacheMetricsFactory.WRITE, null, status, 1, maxWriteDuration.get().intValue(), null).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("APPEND : APP " + _appName + ", Took " + duration + " milliSec for key : " + evcKey);
        }
    }

    public <T> EVCacheFuture[] set(String key, T value, Transcoder<T> tc) throws EVCacheException {
        return this.set(key, value, tc, _timeToLive);
    }

    public <T> EVCacheFuture[] set(String key, T value, int timeToLive) throws EVCacheException {
        return this.set(key, value, (Transcoder<T>) _transcoder, timeToLive);
    }

    public <T> EVCacheFuture[] set(String key, T value) throws EVCacheException {
        return this.set(key, value, (Transcoder<T>) _transcoder, _timeToLive);
    }

    public EVCacheFuture[] delete(String key) throws EVCacheException {
        final EVCacheLatch latch = this.delete(key, null);
        if (latch == null) return new EVCacheFuture[0];
        final List<Future<Boolean>> futures = latch.getAllFutures();
        if (futures == null || futures.isEmpty()) return new EVCacheFuture[0];
        final EVCacheFuture[] eFutures = new EVCacheFuture[futures.size()];
        for (int i = 0; i < futures.size(); i++) {
            final Future<Boolean> future = futures.get(i);
            if (future instanceof EVCacheFuture) {
                eFutures[i] = (EVCacheFuture) future;
            } else if (future instanceof EVCacheOperationFuture) {
                final EVCacheOperationFuture<Boolean> evfuture = (EVCacheOperationFuture<Boolean>)future;
                eFutures[i] = new EVCacheFuture(future, key, _appName, evfuture.getServerGroup(), evfuture.getEVCacheClient());
            } else {
                eFutures[i] = new EVCacheFuture(future, key, _appName, null);
            }
        }
        return eFutures;

    }

    @Override
    public <T> EVCacheLatch delete(String key, Policy policy) throws EVCacheException {
        if (key == null) throw new IllegalArgumentException("Key cannot be null");

        final boolean throwExc = doThrowException();
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        if (clients.length == 0) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.DELETE);
            if (throwExc) throw new EVCacheException("Could not find a client to delete the keyAPP " + _appName
                    + ", Key " + key);
            return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
        }

        final EVCacheKey evcKey = getEVCacheKey(key);
       final EVCacheEvent event = createEVCacheEvent(Arrays.asList(clients), Call.DELETE);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.DELETE);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + key);
                    return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.DELETE);
                return null;
            }
            startEvent(event);
        }

        String status = EVCacheMetricsFactory.SUCCESS;
        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        final EVCacheLatchImpl latch = new EVCacheLatchImpl(policy == null ? Policy.ALL_MINUS_1 : policy, clients.length - _pool.getWriteOnlyEVCacheClients().length, _appName);
        try {
            for (int i = 0; i < clients.length; i++) {
                Future<Boolean> future = clients[i].delete(evcKey.getDerivedKey(clients[i].isDuetClient()), latch);
                if (log.isDebugEnabled() && shouldLog()) log.debug("DELETE : APP " + _appName + ", Future " + future + " for key : " + evcKey);
            }

            if (event != null) {
                if(_eventsUsingLatchFP.get()) {
                    latch.setEVCacheEvent(event);
                    latch.scheduledFutureValidation();
                } else {
                    endEvent(event);
                }
            }
            return latch;
        } catch (Exception ex) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception while deleting the data for APP " + _appName + ", key : " + key, ex);
            status = EVCacheMetricsFactory.ERROR;
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return new EVCacheLatchImpl(policy, 0, _appName);
            throw new EVCacheException("Exception while deleting the data for APP " + _appName + ", key : " + key, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.DELETE.name(), EVCacheMetricsFactory.WRITE, null, status, 1, maxWriteDuration.get().intValue(), null).record(duration, TimeUnit.MILLISECONDS);
            //timer.record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("DELETE : APP " + _appName + " Took " + duration + " milliSec for key : " + key);
        }
    }



    public int getDefaultTTL() {
        return _timeToLive;
    }

    public long incr(String key, long by, long defaultVal, int timeToLive) throws EVCacheException {
        if ((null == key) || by < 0 || defaultVal < 0 || timeToLive < 0) throw new IllegalArgumentException();
        checkTTL(timeToLive, Call.INCR);

        final boolean throwExc = doThrowException();
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        if (clients.length == 0) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.INCR);
            if (log.isDebugEnabled() && shouldLog()) log.debug("INCR : " + _metricPrefix + ":NULL_CLIENT");
            if (throwExc) throw new EVCacheException("Could not find a client to incr the data");
            return -1;
        }

        final EVCacheKey evcKey = getEVCacheKey(key);
        final EVCacheEvent event = createEVCacheEvent(Arrays.asList(clients), Call.INCR);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.INCR);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + key);
                    return -1;
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.INCR);
                return -1;
            }
            startEvent(event);
        }

        String status = EVCacheMetricsFactory.SUCCESS;
        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();

        long currentValue = -1;
        try {
            final long[] vals = new long[clients.length];
            int index = 0;
            for (EVCacheClient client : clients) {
                vals[index] = client.incr(evcKey.getDerivedKey(client.isDuetClient()), by, defaultVal, timeToLive);
                if (vals[index] != -1 && currentValue < vals[index]) {
                    currentValue = vals[index];
                    if (log.isDebugEnabled()) log.debug("INCR : APP " + _appName + " current value = " + currentValue + " for key : " + key + " from client : " + client);
                }
                index++;
            }

            if (currentValue != -1) {
                CachedData cd = null;
                if (log.isDebugEnabled()) log.debug("INCR : APP " + _appName + " current value = " + currentValue + " for key : " + key);
                for (int i = 0; i < vals.length; i++) {
                    if (vals[i] == -1 && currentValue > -1) {
                        if (log.isDebugEnabled()) log.debug("INCR : APP " + _appName + "; Zone " + clients[i].getZone()
                                + " had a value = -1 so setting it to current value = " + currentValue + " for key : " + key);
                        clients[i].incr(evcKey.getDerivedKey(clients[i].isDuetClient()), 0, currentValue, timeToLive);
                    } else if (vals[i] != currentValue) {
                        if(cd == null) cd = clients[i].getTranscoder().encode(String.valueOf(currentValue));
                        if (log.isDebugEnabled()) log.debug("INCR : APP " + _appName + "; Zone " + clients[i].getZone()
                                + " had a value of " + vals[i] + " so setting it to current value = " + currentValue + " for key : " + key);
                        clients[i].set(evcKey.getDerivedKey(clients[i].isDuetClient()), cd, timeToLive);
                    }
                }
            }
            if (event != null) endEvent(event);
            if (log.isDebugEnabled()) log.debug("INCR : APP " + _appName + " returning value = " + currentValue + " for key : " + key);
            return currentValue;
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception incrementing the value for APP " + _appName + ", key : " + key, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return -1;
            throw new EVCacheException("Exception incrementing value for APP " + _appName + ", key : " + key, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.INCR.name(), EVCacheMetricsFactory.WRITE, null, status, 1, maxWriteDuration.get().intValue(), null).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("INCR : APP " + _appName + ", Took " + duration
                    + " milliSec for key : " + key + " with value as " + currentValue);
        }
    }

    public long decr(String key, long by, long defaultVal, int timeToLive) throws EVCacheException {
        if ((null == key) || by < 0 || defaultVal < 0 || timeToLive < 0) throw new IllegalArgumentException();
        checkTTL(timeToLive, Call.DECR);

        final boolean throwExc = doThrowException();
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        if (clients.length == 0) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.DECR);
            if (log.isDebugEnabled() && shouldLog()) log.debug("DECR : " + _metricPrefix + ":NULL_CLIENT");
            if (throwExc) throw new EVCacheException("Could not find a client to decr the data");
            return -1;
        }

        final EVCacheKey evcKey = getEVCacheKey(key);
        final EVCacheEvent event = createEVCacheEvent(Arrays.asList(clients), Call.DECR);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.DECR);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + key);
                    return -1;
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.DECR);
                return -1;
            }
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String status = EVCacheMetricsFactory.SUCCESS;
        long currentValue = -1;
        try {
            final long[] vals = new long[clients.length];
            int index = 0;
            for (EVCacheClient client : clients) {
                vals[index] = client.decr(evcKey.getDerivedKey(client.isDuetClient()), by, defaultVal, timeToLive);
                if (vals[index] != -1 && currentValue < vals[index]) {
                    currentValue = vals[index];
                    if (log.isDebugEnabled()) log.debug("DECR : APP " + _appName + " current value = " + currentValue + " for key : " + key + " from client : " + client);
                }
                index++;
            }

            if (currentValue != -1) {
                CachedData cd = null;
                if (log.isDebugEnabled()) log.debug("DECR : APP " + _appName + " current value = " + currentValue
                        + " for key : " + key);
                for (int i = 0; i < vals.length; i++) {
                    if (vals[i] == -1 && currentValue > -1) {
                        if (log.isDebugEnabled()) log.debug("DECR : APP " + _appName + "; Zone " + clients[i].getZone()
                                + " had a value = -1 so setting it to current value = "
                                + currentValue + " for key : " + key);
                        clients[i].decr(evcKey.getDerivedKey(clients[i].isDuetClient()), 0, currentValue, timeToLive);
                    } else if (vals[i] != currentValue) {
                        if(cd == null) cd = clients[i].getTranscoder().encode(currentValue);
                        if (log.isDebugEnabled()) log.debug("DECR : APP " + _appName + "; Zone " + clients[i].getZone()
                                + " had a value of " + vals[i]
                                        + " so setting it to current value = " + currentValue + " for key : " + key);
                        clients[i].set(evcKey.getDerivedKey(clients[i].isDuetClient()), cd, timeToLive);
                    }
                }
            }

            if (event != null) endEvent(event);
            if (log.isDebugEnabled()) log.debug("DECR : APP " + _appName + " returning value = " + currentValue + " for key : " + key);
            return currentValue;
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception decrementing the value for APP " + _appName + ", key : " + key, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return -1;
            throw new EVCacheException("Exception decrementing value for APP " + _appName + ", key : " + key, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.DECR.name(), EVCacheMetricsFactory.WRITE, null, status, 1, maxWriteDuration.get().intValue(), null).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("DECR : APP " + _appName + ", Took " + duration + " milliSec for key : " + key + " with value as " + currentValue);
        }
    }

    @Override
    public <T> EVCacheLatch replace(String key, T value, Policy policy) throws EVCacheException {
        return replace(key, value, (Transcoder<T>) _transcoder, policy);
    }

    @Override
    public <T> EVCacheLatch replace(String key, T value, Transcoder<T> tc, Policy policy) throws EVCacheException {
        return replace(key, value, (Transcoder<T>) _transcoder, _timeToLive, policy);
    }

    public <T> EVCacheLatch replace(String key, T value,  int timeToLive, Policy policy) throws EVCacheException {
        return replace(key, value, (Transcoder<T>)_transcoder, timeToLive, policy);
    }

    @Override
    public <T> EVCacheLatch replace(String key, T value, Transcoder<T> tc, int timeToLive, Policy policy)
            throws EVCacheException {
        if ((null == key) || (null == value)) throw new IllegalArgumentException();
        checkTTL(timeToLive, Call.REPLACE);

        final boolean throwExc = doThrowException();
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        if (clients.length == 0) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.REPLACE);
            if (throwExc) throw new EVCacheException("Could not find a client to set the data");
            return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
        }

        final EVCacheKey evcKey = getEVCacheKey(key);
        final EVCacheEvent event = createEVCacheEvent(Arrays.asList(clients), Call.REPLACE);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.REPLACE);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + key);
                    return new EVCacheLatchImpl(policy, 0, _appName);
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.REPLACE);
                return null;
            }
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String status = EVCacheMetricsFactory.SUCCESS;
        final EVCacheLatchImpl latch = new EVCacheLatchImpl(policy == null ? Policy.ALL_MINUS_1 : policy, clients.length - _pool.getWriteOnlyEVCacheClients().length, _appName);
        try {
            final EVCacheFuture[] futures = new EVCacheFuture[clients.length];
            CachedData cd = null;
            int index = 0;
            for (EVCacheClient client : clients) {
                if (cd == null) {
                    if (tc != null) {
                        cd = tc.encode(value);
                    } else if ( _transcoder != null) {
                        cd = ((Transcoder<Object>)_transcoder).encode(value);
                    } else {
                        cd = client.getTranscoder().encode(value);
                    }

                    if(hashKey.get()) {
                        final EVCacheValue val = new EVCacheValue(evcKey.getCanonicalKey(client.isDuetClient()), cd.getData(), cd.getFlags(), timeToLive, System.currentTimeMillis());
                        cd = evcacheValueTranscoder.encode(val);
                    }
                }
                final Future<Boolean> future = client.replace(evcKey.getDerivedKey(client.isDuetClient()), cd, timeToLive, latch);
                futures[index++] = new EVCacheFuture(future, key, _appName, client.getServerGroup());
            }
            if (event != null) {
                event.setTTL(timeToLive);
                event.setCachedData(cd);
                if(_eventsUsingLatchFP.get()) {
                    latch.setEVCacheEvent(event);
                    latch.scheduledFutureValidation();
                } else {
                    endEvent(event);
                }
            }
            return latch;
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception setting the data for APP " + _appName + ", key : " + evcKey, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return new EVCacheLatchImpl(policy, 0, _appName);
            throw new EVCacheException("Exception setting data for APP " + _appName + ", key : " + evcKey, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.REPLACE.name(), EVCacheMetricsFactory.WRITE, null, status, 1, maxWriteDuration.get().intValue(), null).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("REPLACE : APP " + _appName + ", Took " + duration + " milliSec for key : " + evcKey);
        }
    }

    @Override
    public String getCachePrefix() {
        return _cacheName;
    }

    public String getAppName() {
        return _appName;
    }

    public String getCacheName() {
        return _cacheName;
    }

    public <T> EVCacheLatch appendOrAdd(String key, T value, Transcoder<T> tc, int timeToLive, Policy policy) throws EVCacheException {
        if ((null == key) || (null == value)) throw new IllegalArgumentException();
        checkTTL(timeToLive, Call.APPEND_OR_ADD);

        final boolean throwExc = doThrowException();
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        if (clients.length == 0) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.APPEND_OR_ADD);
            if (throwExc) throw new EVCacheException("Could not find a client to appendOrAdd the data");
            return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
        }

        final EVCacheKey evcKey = getEVCacheKey(key);
        final EVCacheEvent event = createEVCacheEvent(Arrays.asList(clients), Call.APPEND_OR_ADD);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.APPEND_OR_ADD);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + key);
                    return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.APPEND_OR_ADD);
                return null;
            }
            startEvent(event);
        }
        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        final EVCacheLatchImpl latch = new EVCacheLatchImpl(policy == null ? Policy.ALL_MINUS_1 : policy, clients.length - _pool.getWriteOnlyEVCacheClients().length, _appName);
        String status = EVCacheMetricsFactory.SUCCESS;
        try {
            CachedData cd = null;
            for (EVCacheClient client : clients) {
                if (cd == null) {
                    if (tc != null) {
                        cd = tc.encode(value);
                    } else if ( _transcoder != null) {
                        cd = ((Transcoder<Object>)_transcoder).encode(value);
                    } else {
                        cd = client.getTranscoder().encode(value);
                    }
                }
                final Future<Boolean> future = client.appendOrAdd(evcKey.getDerivedKey(client.isDuetClient()), cd, timeToLive, latch);
                if (log.isDebugEnabled() && shouldLog()) log.debug("APPEND_OR_ADD : APP " + _appName + ", Future " + future + " for key : " + evcKey);
            }
            if (event != null) {
                event.setTTL(timeToLive);
                event.setCachedData(cd);
                if(_eventsUsingLatchFP.get()) {
                    latch.setEVCacheEvent(event);
                    latch.scheduledFutureValidation();
                } else {
                    endEvent(event);
                }
            }
            return latch;
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception while appendOrAdd the data for APP " + _appName + ", key : " + evcKey, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return new EVCacheLatchImpl(policy, 0, _appName);
            throw new EVCacheException("Exception while appendOrAdd data for APP " + _appName + ", key : " + evcKey, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.APPEND_OR_ADD.name(), EVCacheMetricsFactory.WRITE, null, status, 1, maxWriteDuration.get().intValue(), null).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("APPEND_OR_ADD : APP " + _appName + ", Took " + duration + " milliSec for key : " + evcKey);
        }
    }

    public <T> Future<Boolean>[] appendOrAdd(String key, T value, Transcoder<T> tc, int timeToLive) throws EVCacheException {
        final EVCacheLatch latch = this.appendOrAdd(key, value, tc, timeToLive, Policy.ALL_MINUS_1);
        if(latch != null) return latch.getAllFutures().toArray(new Future[latch.getAllFutures().size()]);
        return new EVCacheFuture[0];
    }


    public <T> boolean add(String key, T value, Transcoder<T> tc, int timeToLive) throws EVCacheException {
        final EVCacheLatch latch = add(key, value, tc, timeToLive, Policy.NONE);
        try {
            latch.await(_pool.getOperationTimeout().get(), TimeUnit.MILLISECONDS);
            final List<Future<Boolean>> allFutures = latch.getAllFutures();
            for(Future<Boolean> future : allFutures) {
                if(!future.get()) return false;
            }
            return true;
        } catch (InterruptedException e) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception adding the data for APP " + _appName + ", key : " + key, e);
            final boolean throwExc = doThrowException();
            if(throwExc) throw new EVCacheException("Exception add data for APP " + _appName + ", key : " + key, e);
            return false;
        } catch (ExecutionException e) {
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception adding the data for APP " + _appName + ", key : " + key, e);
            final boolean throwExc = doThrowException();
            if(throwExc) throw new EVCacheException("Exception add data for APP " + _appName + ", key : " + key, e);
            return false;
        }
    }

    @Override
    public <T> EVCacheLatch add(String key, T value, Transcoder<T> tc, int timeToLive, Policy policy) throws EVCacheException {
        if ((null == key) || (null == value)) throw new IllegalArgumentException();
        checkTTL(timeToLive, Call.ADD);

        final boolean throwExc = doThrowException();
        final EVCacheClient[] clients = _pool.getEVCacheClientForWrite();
        if (clients.length == 0) {
            incrementFastFail(EVCacheMetricsFactory.NULL_CLIENT, Call.ADD);
            if (throwExc) throw new EVCacheException("Could not find a client to Add the data");
            return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
        }

        final EVCacheKey evcKey = getEVCacheKey(key);
        final EVCacheEvent event = createEVCacheEvent(Arrays.asList(clients), Call.ADD);
        if (event != null) {
            event.setEVCacheKeys(Arrays.asList(evcKey));
            try {
                if (shouldThrottle(event)) {
                    incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.ADD);
                    if (throwExc) throw new EVCacheException("Request Throttled for app " + _appName + " & key " + key);
                    return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
                }
            } catch(EVCacheException ex) {
                if(throwExc) throw ex;
                incrementFastFail(EVCacheMetricsFactory.THROTTLED, Call.ADD);
                return new EVCacheLatchImpl(policy, 0, _appName); // Fast failure
            }
            startEvent(event);
        }

        final long start = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime();
        String status = EVCacheMetricsFactory.SUCCESS;
        EVCacheLatch latch = null;
        try {
            CachedData cd = null;
            if (cd == null) {
                if (tc != null) {
                    cd = tc.encode(value);
                } else if ( _transcoder != null) {
                    cd = ((Transcoder<Object>)_transcoder).encode(value);
                } else {
                    cd = _pool.getEVCacheClientForRead().getTranscoder().encode(value);
                }
            }
            if(clientUtil == null) clientUtil = new EVCacheClientUtil(_pool);
            latch = clientUtil.add(evcKey, cd, hashKey.get(), evcacheValueTranscoder, timeToLive, policy);
            if (event != null) {
                event.setTTL(timeToLive);
                event.setCachedData(cd);
                if(_eventsUsingLatchFP.get()) {
                    latch.setEVCacheEvent(event);
                    if(latch instanceof EVCacheLatchImpl)
                        ((EVCacheLatchImpl)latch).scheduledFutureValidation();
                } else {
                    endEvent(event);
                }
            }

            return latch;
        } catch (Exception ex) {
            status = EVCacheMetricsFactory.ERROR;
            if (log.isDebugEnabled() && shouldLog()) log.debug("Exception adding the data for APP " + _appName + ", key : " + evcKey, ex);
            if (event != null) {
                event.setStatus(status);
                eventError(event, ex);
            }
            if (!throwExc) return new EVCacheLatchImpl(policy, 0, _appName);
            throw new EVCacheException("Exception adding data for APP " + _appName + ", key : " + evcKey, ex);
        } finally {
            final long duration = EVCacheMetricsFactory.getInstance().getRegistry().clock().wallTime()- start;
            getTimer(Call.ADD.name(), EVCacheMetricsFactory.WRITE, null, status, 1, maxWriteDuration.get().intValue(), null).record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled() && shouldLog()) log.debug("ADD : APP " + _appName + ", Took " + duration + " milliSec for key : " + evcKey);
        }
    }

    private DistributionSummary getTTLDistributionSummary(String operation, String type, String metric) {
        DistributionSummary distributionSummary = distributionSummaryMap.get(operation);
        if(distributionSummary != null) return distributionSummary;

        final List<Tag> tagList = new ArrayList<Tag>(6);
        tagList.addAll(tags);
        tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TAG, operation));
        tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TYPE_TAG, type));
        distributionSummary = EVCacheMetricsFactory.getInstance().getDistributionSummary(metric, tagList);
        distributionSummaryMap.put(operation, distributionSummary);
        return distributionSummary;
    }

    private Timer getTimer(String operation, String operationType, String hit, String status, int tries, long duration, ServerGroup serverGroup) {
        String name = ((hit != null) ? operation + hit : operation);
        if(status != null) name += status;
        if(tries >= 0) name += tries;
        if(serverGroup != null) name += serverGroup.getName();
        //if(_cacheName != null) name += _cacheName;

        Timer timer = timerMap.get(name);
        if(timer != null) return timer;

        final List<Tag> tagList = new ArrayList<Tag>(7);
        tagList.addAll(tags);
        if(operation != null) tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TAG, operation));
        if(operationType != null) tagList.add(new BasicTag(EVCacheMetricsFactory.CALL_TYPE_TAG, operationType));
        if(status != null) tagList.add(new BasicTag(EVCacheMetricsFactory.IPC_RESULT, status));
        if(hit != null) tagList.add(new BasicTag(EVCacheMetricsFactory.CACHE_HIT, hit));
        switch(tries) {
            case 0 :
            case 1 :
                tagList.add(new BasicTag(EVCacheMetricsFactory.ATTEMPT, EVCacheMetricsFactory.INITIAL));
                break;
            case 2 :
                tagList.add(new BasicTag(EVCacheMetricsFactory.ATTEMPT, EVCacheMetricsFactory.SECOND));
                break;
            default:
                tagList.add(new BasicTag(EVCacheMetricsFactory.ATTEMPT, EVCacheMetricsFactory.THIRD_UP));
                break;
        }

//        if(tries == 0) tagList.add(new BasicTag(EVCacheMetricsFactory.ATTEMPT, String.valueOf(tries)));
        if(serverGroup != null) {
            tagList.add(new BasicTag(EVCacheMetricsFactory.SERVERGROUP, serverGroup.getName()));
            tagList.add(new BasicTag(EVCacheMetricsFactory.ZONE, serverGroup.getZone()));
        }

        timer = EVCacheMetricsFactory.getInstance().getPercentileTimer(EVCacheMetricsFactory.OVERALL_CALL, tagList, Duration.ofMillis(duration));
        timerMap.put(name, timer);
        return timer;
    }

    protected List<Tag> getTags() {
        return tags;
    }

}
