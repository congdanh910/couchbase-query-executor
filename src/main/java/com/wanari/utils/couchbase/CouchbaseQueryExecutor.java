package com.wanari.utils.couchbase;

import com.couchbase.client.java.document.json.JsonObject;
import com.couchbase.client.java.query.N1qlQuery;
import com.couchbase.client.java.query.Statement;
import com.couchbase.client.java.query.dsl.Expression;
import com.couchbase.client.java.query.dsl.Sort;
import com.couchbase.client.java.query.dsl.path.FromPath;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanari.utils.couchbase.converters.*;
import com.wanari.utils.couchbase.exceptions.NonUniqueResultException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.couchbase.core.CouchbaseTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.couchbase.client.java.query.Select.select;
import static com.couchbase.client.java.query.dsl.Expression.i;
import static com.couchbase.client.java.query.dsl.Expression.x;

@Component
public class CouchbaseQueryExecutor<T> {

    @Value("${couchbase-query-executor.with-sync-gateway:false}")
    private Boolean withSyncGateway;

    @Value("${couchbase-query-executor.use-default-id-fields:true}")
    private Boolean useDefaultIdFields;

    public static final String CONTAINS_FILTER = "_contains";
    public static final String FROM_FILTER = "_from";
    public static final String TO_FILTER = "_to";
    public static final String NOT_FILTER = "_not";
    public static final String IN_FILTER = "_in";
    public static final String NULL_FILTER = "_null";
    public static final String NOT_NULL_FILTER = "_notnull";
    public static final String MISSING_FILTER = "_missing";
    public static final String NULL_OR_MISSING_FILTER = "_nullormissing";
    private static final String IGNORE_CASE_ORDER = "_ignorecase";
    private DataConverter<T> converter;
    private final ObjectMapper objectMapper;

    private final CouchbaseQueryExecutorConfiguration couchbaseConfiguration;

    public CouchbaseQueryExecutor(CouchbaseQueryExecutorConfiguration couchbaseConfiguration, ObjectMapper objectMapper) {
        this.couchbaseConfiguration = couchbaseConfiguration;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    private void init() {
        if(withSyncGateway) {
            if(useDefaultIdFields) {
                converter = new SyncGatewayDataConverter<>(objectMapper);
            } else {
                converter = new SyncGatewayDataConverterWithReflection<>(objectMapper);
            }
        } else {
            if(useDefaultIdFields) {
                converter = new CouchbaseDataConverter<>(objectMapper);
            } else {
                converter = new CouchbaseDataConverterWithReflection<>(objectMapper);
            }
        }
    }

    public DataConverter<T> getConverter() {
        return converter;
    }

    public void setConverter(DataConverter<T> converter) {
        this.converter = converter;
    }

    private CouchbaseTemplate createTemplate() {
        try {
            return new CouchbaseTemplate(couchbaseConfiguration.couchbaseClusterInfo(), couchbaseConfiguration.couchbaseClient());
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<T> findOne(JsonObject params, Class<T> clazz) {
        List<T> documents = find(params, clazz);
        return asOptional(documents, params);
    }

    public Page<T> find(JsonObject params, Pageable pageable, Class<T> clazz) {
        CouchbaseTemplate template = createTemplate();

        Statement query = createQueryStatement(params, pageable);
        N1qlQuery queryWithParameter = N1qlQuery.parameterized(query, params);

        List<T> data = convertToDataList(template.findByN1QLProjection(queryWithParameter, LinkedHashMap.class), clazz);
        Integer count = count(params);

        return new PageImpl<>(data, pageable, count);
    }

    public List<T> find(JsonObject params, Class<T> clazz) {
        CouchbaseTemplate template = createTemplate();

        Statement query = createQueryStatement(params);
        N1qlQuery queryWithParameter = N1qlQuery.parameterized(query, params);

        return convertToDataList(template.findByN1QLProjection(queryWithParameter, LinkedHashMap.class), clazz);
    }

    private List<T> convertToDataList(List<LinkedHashMap> queriedList, Class<T> clazz) {
        return queriedList.stream()
            .map(hashMap -> converter.apply(hashMap, clazz))
            .collect(Collectors.toList());
    }

    public Integer count(JsonObject params) {
        CouchbaseTemplate template = createTemplate();

        Statement query = createCountStatement(params);
        N1qlQuery queryWithParams = N1qlQuery.parameterized(query, params);
        LinkedHashMap countMap = ((LinkedHashMap) template.findByN1QLProjection(queryWithParams, Object.class).get(0));

        return ((Integer) countMap.get("count"));
    }


    public Integer sum(JsonObject params, String field) {
        CouchbaseTemplate template = createTemplate();

        Statement query = createSumStatement(params, field);
        N1qlQuery queryWithParams = N1qlQuery.parameterized(query, params);
        LinkedHashMap sumMap = ((LinkedHashMap) template.findByN1QLProjection(queryWithParams, Object.class).get(0));

        return ((Integer) sumMap.get("sum"));
    }

    private Statement createCountStatement(JsonObject params) {
        Expression bucketName = i(couchbaseConfiguration.getBucketName());
        return count(bucketName)
            .from(bucketName)
            .where(composeWhere(bucketName, params))
            .groupBy(meta(bucketName));
    }

    private Statement createSumStatement(JsonObject params, String field) {
        Expression bucketName = i(couchbaseConfiguration.getBucketName());
        return sum(bucketName, field)
            .from(bucketName)
            .where(composeWhere(bucketName, params))
            .groupBy(meta(bucketName));
    }

    private Statement createQueryStatement(JsonObject params, Pageable pageable) {
        Expression bucketName = i(couchbaseConfiguration.getBucketName());
        return selectWithMeta(bucketName)
            .from(bucketName)
            .where(composeWhere(bucketName, params))
            .orderBy(fromPageable(pageable))
            .limit(pageable.getPageSize())
            .offset(pageable.getOffset());
    }

    private Statement createQueryStatement(JsonObject params) {
        Expression bucketName = i(couchbaseConfiguration.getBucketName());
        return selectWithMeta(bucketName)
            .from(bucketName)
            .where(composeWhere(bucketName, params));
    }

    private FromPath count(Expression bucketName) {
        return select("count(*) as count, meta(" + bucketName + ").id AS id ");
    }

    private FromPath sum(Expression bucketName, String field) {
        return select("sum(" + field + ") as sum, meta(" + bucketName + ").id AS id ");
    }

    private FromPath selectWithMeta(Expression bucketName) {
        return select(bucketName + " as data, meta(" + bucketName + ").id AS id ");
    }

    private String meta(Expression bucketName) {
        return "meta(" + bucketName + ").id";
    }

    private Expression composeWhere(Expression bucketName, JsonObject params) {
        List<Expression> expressions = params.getNames()
            .stream()
            .map(this::createExpression)
            .collect(Collectors.toList());

        expressions.add(x("meta(" + bucketName + ").id NOT LIKE \"_sync:%\""));

        return expressions
            .stream()
            .reduce(Expression::and)
            .get();
    }

    private Expression createExpression(String key) {
        String propertyKey = key;

        if(key.endsWith(CONTAINS_FILTER)) {
            propertyKey = key.substring(0, key.length() - CONTAINS_FILTER.length());
            return createContainsExpression(propertyKey, key);
        } else if(key.endsWith(FROM_FILTER)) {
            propertyKey = key.substring(0, key.length() - FROM_FILTER.length());
            return createGreaterThanOrEqualsExpression(propertyKey, key);
        } else if(key.endsWith(TO_FILTER)) {
            propertyKey = key.substring(0, key.length() - TO_FILTER.length());
            return createLessThanOrEqualsExpression(propertyKey, key);
        } else if(key.endsWith(NOT_FILTER)) {
            propertyKey = key.substring(0, key.length() - NOT_FILTER.length());
            return createNotEqualsExpression(propertyKey, key);
        } else if(key.endsWith(IN_FILTER)) {
            propertyKey = key.substring(0, key.length() - IN_FILTER.length());
            return createInExpression(propertyKey, key);
        } else if(key.endsWith(NULL_FILTER)) {
            propertyKey = propertyKey.substring(0, propertyKey.length() - NULL_FILTER.length());
            return createNullExpression(propertyKey, key);
        } else if(key.endsWith(NOT_NULL_FILTER)) {
            propertyKey = propertyKey.substring(0, propertyKey.length() - NOT_NULL_FILTER.length());
            return createNotNullExpression(propertyKey, key);
        } else if(key.endsWith(MISSING_FILTER)) {
            propertyKey = propertyKey.substring(0, propertyKey.length() - MISSING_FILTER.length());
            return createMissingExpression(propertyKey, key);
        } else if(key.endsWith(NULL_OR_MISSING_FILTER)) {
            propertyKey = propertyKey.substring(0, propertyKey.length() - NULL_OR_MISSING_FILTER.length());
            return createNullOrMissingExpression(propertyKey, key);
        } else {
            return createEqualsExpression(propertyKey, key);
        }
    }

    private Expression createInExpression(String propertyKey, String key) {
        return x(propertyKey).in("$" + key);
    }

    private Expression createGreaterThanOrEqualsExpression(String propertyKey, String key) {
        return x(propertyKey).gte("$" + key);
    }

    private Expression createLessThanOrEqualsExpression(String propertyKey, String key) {
        return x(propertyKey).lte("$" + key);
    }

    private Expression createEqualsExpression(String propertyKey, String key) {
        return x(propertyKey).eq("$" + key);
    }

    private Expression createNotEqualsExpression(String propertyKey, String key) {
        return x(propertyKey).ne("$" + key);
    }

    private Expression createContainsExpression(String propertyKey, String key) {
        return x("CONTAINS(LOWER(" + propertyKey + "), LOWER($" + key + "))");
    }

    private Expression createNullExpression(String propertyKey, String key) {
        return x(propertyKey + " IS NULL");
    }

    private Expression createNotNullExpression(String propertyKey, String key) {
        return x(propertyKey + " IS NOT NULL");
    }

    private Expression createMissingExpression(String propertyKey, String key) {
        return x(propertyKey + " IS MISSING");
    }

    private Expression createNullOrMissingExpression(String propertyKey, String key) {
        return x(propertyKey + " IS NULL OR " + propertyKey + " IS MISSING");
    }

    private String lowerCase(String input) {
        return "LOWER(" + input + ")";
    }

    private Sort[] fromPageable(Pageable pageable) {
        List<Sort> orderBy = new ArrayList<>();
        pageable.getSort().forEach(pageableOrder -> {
            switch(pageableOrder.getDirection()) {
                case ASC:
                    if(pageableOrder.getProperty().endsWith(IGNORE_CASE_ORDER)) {
                        String property = pageableOrder.getProperty().substring(0, pageableOrder.getProperty().length() - IGNORE_CASE_ORDER.length());
                        orderBy.add(Sort.asc(lowerCase(property)));
                    } else {
                        orderBy.add(Sort.asc(pageableOrder.getProperty()));
                    }
                    break;
                case DESC:
                    if(pageableOrder.getProperty().endsWith(IGNORE_CASE_ORDER)) {
                        String property = pageableOrder.getProperty().substring(0, pageableOrder.getProperty().length() - IGNORE_CASE_ORDER.length());
                        orderBy.add(Sort.desc(lowerCase(property)));
                    } else {
                        orderBy.add(Sort.desc(pageableOrder.getProperty()));
                    }
                    break;
            }
        });
        return orderBy.toArray(new Sort[orderBy.size()]);
    }

    private Optional<T> asOptional(List<T> documents, JsonObject params) {
        if(documents.isEmpty()) {
            return Optional.empty();
        }
        if(documents.size() == 1) {
            return Optional.of(documents.get(0));
        }
        throw new NonUniqueResultException(params);
    }
}
