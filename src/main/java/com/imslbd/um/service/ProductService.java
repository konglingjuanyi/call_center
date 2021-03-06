package com.imslbd.um.service;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.imslbd.um.*;
import com.imslbd.um.model.*;
import io.crm.ErrorCodes;
import io.crm.transformation.JsonTransformationPipeline;
import io.crm.transformation.impl.json.object.ConverterTransformation;
import io.crm.transformation.impl.json.object.DefaultValueTransformation;
import io.crm.transformation.impl.json.object.IncludeExcludeTransformation;
import io.crm.transformation.impl.json.object.RemoveNullsTransformation;
import io.crm.validator.ValidationPipeline;
import io.crm.validator.ValidationResult;
import io.crm.validator.ValidationResultBuilder;
import io.crm.validator.Validator;
import io.crm.validator.composer.FieldValidatorComposer;
import io.crm.validator.composer.JsonObjectValidatorComposer;
import io.crm.promise.Decision;
import io.crm.promise.Promises;
import io.crm.promise.intfs.Defer;
import io.crm.promise.intfs.MapToHandler;
import io.crm.util.ExceptionUtil;
import io.crm.util.Util;
import io.crm.util.touple.MutableTpl2;
import io.crm.util.touple.immutable.Tpls;
import io.crm.web.util.Converters;
import io.crm.web.util.Pagination;
import io.crm.web.util.WebUtils;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.imslbd.um.service.Services.AUTH_TOKEN;
import static com.imslbd.um.service.Services.converters;

/**
 * Created by shahadat on 4/3/16.
 */
public class ProductService {
    private final AtomicLong id;

    public static final Logger LOGGER = LoggerFactory.getLogger(ProductService.class);
    private static final java.lang.String SIZE = "size";
    private static final String PAGE = "page";
    private static final String HEADERS = "headers";
    private static final String PAGINATION = "pagination";
    private static final String DATA = "data";
    private static final Integer DEFAULT_PAGE_SIZE = 1000;
    private static final String VALIDATION_ERROR = "validationError";
    private static final String TABLE_NAME = Tables.products.name();
    private static final String PRODUCT_UNIT_PRICES_TABLE = "productUnitPrices";

    private final Vertx vertx;
    private final JDBCClient jdbcClient;
    private final RemoveNullsTransformation removeNullsTransformation;

    private static final String PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";

    private final IncludeExcludeTransformation includeExcludeTransformation;
    private final IncludeExcludeTransformation unitIncludeExcludeTransformation;
    private final ConverterTransformation converterTransformation;
    private final ConverterTransformation unitConverterTransformation;
    private final DefaultValueTransformation defaultValueTransformation;

    private final JsonTransformationPipeline transformationPipeline;
    private final ValidationPipeline<JsonObject> validationPipeline;
    private final ValidationPipeline<JsonObject> unitValidationPipeline;
    private final List<String> productFields;
    private final List<String> productUnitPriceFields;
    private final List<String> unitFields;

    public ProductService(JDBCClient jdbcClient, String[] fields, String[] priceFields, String[] unitFields, long maxId, Vertx vertx) {
        this.vertx = vertx;
        this.jdbcClient = jdbcClient;

        id = new AtomicLong(maxId + 1);

        productFields = ImmutableList.copyOf(fields);
        productUnitPriceFields = ImmutableList.copyOf(priceFields);
        this.unitFields = ImmutableList.copyOf(unitFields);

        removeNullsTransformation = new RemoveNullsTransformation();

        includeExcludeTransformation = new IncludeExcludeTransformation(
            ImmutableSet.<String>builder().addAll(
                Arrays.asList(fields)).build(), null);
        unitIncludeExcludeTransformation = new IncludeExcludeTransformation(ImmutableSet.copyOf(priceFields), ImmutableSet.of(
            Unit.ID, User.CREATED_BY, User.CREATE_DATE, User.UPDATED_BY, User.UPDATE_DATE));

        converterTransformation = new ConverterTransformation(converters(fields, TABLE_NAME));
        unitConverterTransformation = new ConverterTransformation(converters(priceFields, PRODUCT_UNIT_PRICES_TABLE));

        defaultValueTransformation = new DefaultValueTransformation(
            new JsonObject()
                .put(Unit.UPDATED_BY, 0)
                .put(Unit.CREATED_BY, 0)
        );

        transformationPipeline = new JsonTransformationPipeline(
            ImmutableList.of(
                new IncludeExcludeTransformation(null, ImmutableSet.of(User.CREATED_BY, User.CREATE_DATE, User.UPDATED_BY, User.UPDATE_DATE)),
                removeNullsTransformation,
                converterTransformation,
                defaultValueTransformation
            )
        );

        validationPipeline = new ValidationPipeline<>(ImmutableList.copyOf(validators()));
        unitValidationPipeline = new ValidationPipeline<>(ImmutableList.copyOf(unitValidators()));
    }

    private List<Validator<JsonObject>> unitValidators() {
        List<Validator<JsonObject>> list = new ArrayList<>();

        return new JsonObjectValidatorComposer(list, Um.messageBundle)
            .field(Unit.ID, fieldValidatorComposer -> fieldValidatorComposer.numberType().positive().notNull().nonZero())
            .field(Unit.NAME, fieldValidatorComposer1 -> fieldValidatorComposer1.stringType().notNullEmptyOrWhiteSpace())
            .field(Unit.FULL_NAME, fieldValidatorComposer2 -> fieldValidatorComposer2.stringType().notNullEmptyOrWhiteSpace())
            .field(Unit.REMARKS, fieldValidatorComposer3 -> fieldValidatorComposer3.stringType().notNullEmptyOrWhiteSpace())
            .getValidatorList()
            ;
    }

    private List<Validator<JsonObject>> validators() {
        List<Validator<JsonObject>> validators = new ArrayList<>();

        validators.add(js -> {
            JsonArray array = js.getJsonArray(Product.PRICES, Util.EMPTY_JSON_ARRAY);
            if (array.size() <= 0) {
                return new ValidationResultBuilder()
                    .setErrorCode(UmErrorCodes.PRODUCT_PRICE_MISSING_VALIDATION_ERROR.code())
                    .setField(Product.PRICES)
                    .setValue(js.getValue(Product.PRICES))
                    .createValidationResult();
            } else {
                return null;
            }
        });

        return new JsonObjectValidatorComposer(validators, Um.messageBundle)
            .field(Product.NAME,
                fieldValidatorComposer -> fieldValidatorComposer
                    .stringType()
                    .notNullEmptyOrWhiteSpace())
            .field(Product.MANUFACTURER_PRICE,
                fieldValidatorComposer -> fieldValidatorComposer
                    .numberType()
                    .notNull().nonZero().positive())
            .field(Product.MANUFACTURER_PRICE_UNIT_ID,
                fieldValidatorComposer -> fieldValidatorComposer
                    .numberType()
                    .notNull().nonZero().positive())
            .field(Product.REMARKS,
                FieldValidatorComposer::stringType)
            .field(Product.SKU,
                FieldValidatorComposer::stringType)
            .getValidatorList()
            ;
    }

    public void findAll(Message<JsonObject> message) {

        final String pSel = productFields.stream().map(field -> "p." + field).collect(Collectors.joining(", "));
        final String prSel = productUnitPriceFields.stream().map(field -> "up." + field).collect(Collectors.joining(", "));
        final String uSel = unitFields.stream().map(field -> "u." + field).collect(Collectors.joining(", "));
        final String uSel2 = unitFields.stream().map(field -> "u2." + field).collect(Collectors.joining(", "));

        Promises.from(message.body())
            .map(jaa -> jaa == null ? new JsonObject() : jaa)
            .map(removeNullsTransformation::transform)
            .then(params -> {
                int page = params.getInteger(PAGE, 1);
                int size = params.getInteger(SIZE, DEFAULT_PAGE_SIZE);

                final String from = "from " + TABLE_NAME + " p " +
                    "join " + Tables.productUnitPrices + " up on up.productId = p.id " +
                    "join " + Tables.units + " u on u.id = up.unitId " +
                    "join " + Tables.units + " u2 on u2.id = p.manufacturerPriceUnitId";

                final JsonArray prmsArray = new JsonArray();

                String where;

                {
                    String whr = params.fieldNames()
                        .stream()
                        .peek(nm -> prmsArray.add(params.getValue(nm)))
                        .map(nm -> nm + " = ?")
                        .collect(Collectors.joining(" and "));
                    where = whr.isEmpty() ? "" : "where " + whr;
                }

                String fromWhere = from + " " + where;

                String groupBy = "group by p.id, up.productId, up.unitId";

                Promises.when(
                    WebUtils.query("select count(*) as totalCount " + fromWhere, prmsArray, jdbcClient)
                        .map(resultSet -> resultSet.getResults().get(0).getLong(0)),
                    WebUtils.query(
                        "select " + pSel + ", " + prSel + ", " + uSel + ", " + uSel2 + " " +
                            fromWhere + " " + groupBy + " " +
                            UmUtils.limitOffset(page, size), prmsArray, jdbcClient)
                        .map(resultSet3 ->
                            Tpls.of(new JsonObject()
                                .put(HEADERS, resultSet3.getColumnNames()
                                    .stream()
                                    .map(WebUtils::describeField)
                                    .collect(Collectors.toList())), resultSet3.getResults()))
                        .map(tpl21 -> tpl21.apply(
                            (jsonObject, list) -> {

                                final int priceLimit = productFields.size() + productUnitPriceFields.size();
                                final int priceUnitLimit = priceLimit + unitFields.size();
                                final int unitLimit = priceUnitLimit + unitFields.size();

                                final int idIndex = productFields.indexOf(Product.ID);
                                final int priceIdIndex = productFields.size() + productUnitPriceFields.indexOf(ProductUnitPrice.ID);
                                final int unitIdIndex = priceLimit + unitFields.indexOf(Unit.ID);
                                final int manufacturerUnitIdIndex = priceUnitLimit + unitFields.size();

                                HashMap<Object, JsonObject> productsMap = new HashMap<>();
                                HashMap<Object, JsonObject> priceMap = new HashMap<>();

                                list.forEach(jsonArray -> {

                                    final Object id = jsonArray.getValue(idIndex);

                                    JsonObject product = productsMap.get(id);

                                    if (product == null) {
                                        product = new JsonObject();
                                        productsMap.put(id, product);

                                        for (int i = 0; i < productFields.size(); i++) {
                                            product.put(productFields.get(i), jsonArray.getValue(i));
                                        }

                                        Double price = product.getDouble(Product.MANUFACTURER_PRICE);
                                        JsonObject unit = new JsonObject();
                                        for (int i = priceUnitLimit; i < unitLimit; i++) {
                                            unit.put(unitFields.get(i - priceUnitLimit), jsonArray.getValue(i));
                                        }

                                        product.put(Product.MANUFACTURER_PRICE,
                                            new JsonObject()
                                                .put(ProductUnitPrice.AMOUNT, price)
                                                .put(ProductUnitPrice.UNIT, unit));

                                        product.put(Product.PRICES, new ArrayList<>());

                                    }

                                    final Object productId = jsonArray.getValue(priceIdIndex);
                                    JsonObject price = priceMap.get(productId);
                                    if (price == null) {
                                        price = new JsonObject();
                                        priceMap.put(productId, price);

                                        for (int i = productFields.size(); i < priceLimit; i++) {
                                            price.put(productUnitPriceFields.get(i - productFields.size()), jsonArray.getValue(i));
                                        }

                                        price.put(ProductUnitPrice.AMOUNT, price.getValue(ProductUnitPrice.PRICE));


                                        final JsonObject unit = new JsonObject();

                                        for (int i = priceLimit; i < priceUnitLimit; i++) {
                                            unit.put(unitFields.get(i - priceLimit), jsonArray.getValue(i));
                                        }

                                        price.put(ProductUnitPrice.UNIT, unit);

                                    }
                                });

                                priceMap.forEach((id, price) -> {
                                    final Object productId = price.getValue(ProductUnitPrice.PRODUCT_ID);
                                    productsMap.get(productId).getJsonArray(Product.PRICES).add(price);
                                });

                                return
                                    jsonObject
                                        .put(DATA, productsMap.values().stream()
                                            .sorted(
                                                (o1, o2) -> o1.getString(Product.NAME)
                                                    .compareToIgnoreCase(o2.getString(Product.NAME)))
                                            .collect(Collectors.toList()));
                            })))
                    .map(tpl2 -> tpl2.apply(
                        (totalCount, js) ->
                            js.put(PAGINATION,
                                new Pagination(page, size, totalCount).toJson())))
                    .then(message::reply)
                    .error(e -> ExceptionUtil.fail(message, e))
                ;
            })
            .error(e -> ExceptionUtil.fail(message, e))
        ;
    }

    public void find(Message<Object> message) {

        final String pSel = productFields.stream().map(field -> "p." + field).collect(Collectors.joining(", "));
        final String prSel = productUnitPriceFields.stream().map(field -> "up." + field).collect(Collectors.joining(", "));
        final String uSel = unitFields.stream().map(field -> "u." + field).collect(Collectors.joining(", "));

        Promises
            .callable(message::body)
            .then(
                id -> WebUtils.query(
                    "select " + pSel + ", " + prSel + ", " + uSel + " " +
                        "from " + TABLE_NAME + " p " +
                        "join " + Tables.productUnitPrices + " up on up.productId = p.id " +
                        "join " + Tables.units + " u on u.id = up.unitId " +
                        "where p.id = " + id, jdbcClient)
                    .decide(resultSet -> resultSet.getNumRows() < 1 ? PRODUCT_NOT_FOUND : Decision.CONTINUE)
                    .on(PRODUCT_NOT_FOUND,
                        rs ->
                            message.reply(
                                new JsonObject()
                                    .put(Services.RESPONSE_CODE, UmErrorCodes.PRODUCT_NOT_FOUND.code())
                                    .put(Services.MESSAGE_CODE, UmErrorCodes.PRODUCT_NOT_FOUND.messageCode())
                                    .put(Services.MESSAGE,
                                        Um.messageBundle.translate(
                                            UmErrorCodes.PRODUCT_NOT_FOUND.messageCode(),
                                            new JsonObject()
                                                .put(Product.ID, id))),
                                new DeliveryOptions()
                                    .addHeader(Services.RESPONSE_CODE,
                                        Util.toString(UmErrorCodes.PRODUCT_NOT_FOUND.code()))
                            ))
                    .contnue(
                        rs -> Promises.from(rs)
                            .map(rset -> {
                                JsonObject product = new JsonObject();
                                ImmutableList.Builder<JsonObject> builder = ImmutableList.builder();

                                List<JsonArray> results = rs.getResults();
                                JsonArray array = results.get(0);

                                for (int i = 0; i < productFields.size(); i++) {
                                    product.put(productFields.get(i), array.getValue(i));
                                }

                                final int length = productFields.size() + productUnitPriceFields.size();
                                final int len = length + unitFields.size();

                                results.forEach(jsonArray -> {

                                    JsonObject productUnitPrice = new JsonObject();

                                    for (int i = productFields.size(); i < length; i++) {
                                        productUnitPrice.put(productUnitPriceFields.get(i - productFields.size()),
                                            jsonArray.getValue(i));
                                    }

                                    final JsonObject unit = new JsonObject();
                                    for (int j = length; j < len; j++) {
                                        unit.put(unitFields.get(j - length), jsonArray.getValue(j));
                                    }

                                    productUnitPrice.put(ProductUnitPrice.UNIT, unit);
                                    productUnitPrice.put(ProductUnitPrice.AMOUNT, productUnitPrice.getValue(ProductUnitPrice.PRICE));

                                    builder.add(productUnitPrice);
                                });

                                return product.put(Product.PRICES, builder.build());
                            })
                            .mapToPromise(product -> WebUtils.query(
                                "select * from " + Tables.units + " " +
                                    "where " + Unit.ID + " = " + product.getValue(Product.MANUFACTURER_PRICE_UNIT_ID), jdbcClient)
                                .map(rss -> rss.getRows().stream().findFirst().orElse(new JsonObject()))
                                .map(unit -> product.put(Product.MANUFACTURER_PRICE,
                                    new JsonObject()
                                        .put(ProductUnitPrice.AMOUNT, product.getDouble(Product.MANUFACTURER_PRICE))
                                        .put(ProductUnitPrice.UNIT, unit)))
                            )
                            .then(p -> {
                                System.out.println(p.encodePrettily());
                            })
                            .then(message::reply))
                    .error(e -> ExceptionUtil.fail(message, e))
            )
            .error(e -> ExceptionUtil.fail(message, e))
        ;
    }

    public void create(Message<JsonObject> message) {

        final JsonObject user = new JsonObject(message.headers().get(AUTH_TOKEN));

        System.out.println();
        Promises.callable(() -> transformationPipeline.transform(message.body()))
            .decideAndMap(
                product -> {

                    List<ValidationResult> validationResults = validationPipeline.validate(product);

                    return validationResults != null
                        ? Decision.of(VALIDATION_ERROR, validationResults)
                        : Decision.of(Decision.CONTINUE, product);
                })
            .on(VALIDATION_ERROR,
                rsp -> {
                    List<ValidationResult> validationResults = (List<ValidationResult>) rsp;
                    System.out.println("VALIDATION_RESULTS: " + validationResults);
                    message.reply(
                        new JsonObject()
                            .put(Services.RESPONSE_CODE, ErrorCodes.VALIDATION_ERROR.code())
                            .put(Services.MESSAGE_CODE, ErrorCodes.VALIDATION_ERROR.messageCode())
                            .put(Services.MESSAGE,
                                Um.messageBundle.translate(ErrorCodes.VALIDATION_ERROR.messageCode(),
                                    new JsonObject().put(Services.VALIDATION_RESULTS, validationResults)))
                            .put(Services.VALIDATION_RESULTS,
                                validationResults.stream()
                                    .map(v -> v.addAdditionals(Services.ERROR_CODES_MAP.get(v.getErrorCode())))
                                    .peek(v -> v.message(
                                        Um.messageBundle.translate(
                                            v.getAdditionals().getString(Services.MESSAGE_CODE), v.toJson())))
                                    .map(ValidationResult::toJson)
                                    .collect(Collectors.toList())),
                        new DeliveryOptions()
                            .addHeader(Services.RESPONSE_CODE, ErrorCodes.VALIDATION_ERROR.code() + "")
                    );
                })
            .contnue(
                rsp -> {
                    JsonObject product = (JsonObject) rsp;

                    WebUtils.getConnection(jdbcClient)
                        .mapToPromise(con -> {
                            final Defer<Void> defer = Promises.defer();
                            con.setAutoCommit(false, Util.makeDeferred(defer));
                            return defer.promise().map(v -> con)
                                .error(e -> con.close());
                        })
                        .then(con -> {

                            try {
                                List<JsonObject> priceList = product.getJsonArray(Product.PRICES).getList();

                                final long newId = id.getAndIncrement();

                                final JsonObject prod = includeExcludeTransformation
                                    .transform(product)
                                    .put(Product.ID, newId);

                                final List<JsonObject> newPriceList = priceList.stream()
                                    .map(removeNullsTransformation::transform)
                                    .map(unitConverterTransformation::transform)
                                    .map(unitIncludeExcludeTransformation::transform)
                                    .map(js ->
                                        js
                                            .put(ProductUnitPrice.PRODUCT_ID, newId)
                                            .put(Unit.CREATED_BY, user.getLong(User.ID))
                                            .put(User.CREATE_DATE, Converters.toMySqlDateString(new Date()))
                                            .put(Unit.UPDATED_BY, 0))
                                    .collect(Collectors.toList());

                                Promises
                                    .when(
                                        WebUtils.create(TABLE_NAME,
                                            prod, con)
                                            .map(updateResult -> updateResult.getKeys().getLong(0)),
                                        WebUtils.createMulti(PRODUCT_UNIT_PRICES_TABLE, newPriceList, con)
                                    )
                                    .mapToPromise(t -> {
                                        Defer<Void> defer = Promises.defer();
                                        con.commit(Util.makeDeferred(defer));
                                        return defer.promise()
                                            .map((MapToHandler<Void, MutableTpl2<Long, UpdateResult>>) (v -> t));
                                    })
                                    .then(tpl2 -> tpl2.accept((id, list) -> {
                                        message.reply(id);
                                    }))
                                    .then(
                                        v -> vertx.eventBus().publish(
                                            UmEvents.PRODUCT_CREATED,
                                            prod
                                                .put(Product.PRICES, newPriceList)
                                                .put(User.CREATED_BY, user)))
                                    .error(e -> ExceptionUtil.fail(message, e))
                                    .complete(p -> con.close())
                                ;

                            } catch (Exception e) {
                                con.close();
                                ExceptionUtil.fail(message, e);
                            }
                        })
                        .error(e -> ExceptionUtil.fail(message, e))
                    ;
                })
            .error(e ->
                ExceptionUtil.fail(message, e));
    }

    public void update(Message<JsonObject> message) {

        final JsonObject user = new JsonObject(message.headers().get(AUTH_TOKEN));

        System.out.println();
        Promises.callable(() -> transformationPipeline.transform(message.body()))
            .decideAndMap(
                product -> {

                    List<ValidationResult> validationResults = validationPipeline.validate(product);

                    return validationResults != null
                        ? Decision.of(VALIDATION_ERROR, validationResults)
                        : Decision.of(Decision.CONTINUE, product);
                })
            .on(VALIDATION_ERROR,
                rsp -> {
                    List<ValidationResult> validationResults = (List<ValidationResult>) rsp;
                    System.out.println("VALIDATION_RESULTS: " + validationResults);
                    message.reply(
                        new JsonObject()
                            .put(Services.RESPONSE_CODE, ErrorCodes.VALIDATION_ERROR.code())
                            .put(Services.MESSAGE_CODE, ErrorCodes.VALIDATION_ERROR.messageCode())
                            .put(Services.MESSAGE,
                                Um.messageBundle.translate(ErrorCodes.VALIDATION_ERROR.messageCode(),
                                    new JsonObject().put(Services.VALIDATION_RESULTS, validationResults)))
                            .put(Services.VALIDATION_RESULTS,
                                validationResults.stream()
                                    .map(v -> v.addAdditionals(Services.ERROR_CODES_MAP.get(v.getErrorCode())))
                                    .peek(v -> v.message(
                                        Um.messageBundle.translate(
                                            v.getAdditionals().getString(Services.MESSAGE_CODE), v.toJson())))
                                    .map(ValidationResult::toJson)
                                    .collect(Collectors.toList())),
                        new DeliveryOptions()
                            .addHeader(Services.RESPONSE_CODE, ErrorCodes.VALIDATION_ERROR.code() + "")
                    );
                })
            .contnue(
                rsp -> {
                    JsonObject product = (JsonObject) rsp;

                    WebUtils.getConnection(jdbcClient)
                        .mapToPromise(con -> {
                            final Defer<Void> defer = Promises.defer();
                            con.setAutoCommit(false, Util.makeDeferred(defer));
                            return defer.promise().map(v -> con)
                                .error(e -> con.close());
                        })
                        .mapToPromise(
                            con -> WebUtils.delete(Tables.productUnitPrices.name(), new JsonObject()
                                .put(ProductUnitPrice.PRODUCT_ID, product.getValue(Product.ID)), con)
                                .map(v -> (SQLConnection) con))

                        .then(con -> {

                            try {

                                List<JsonObject> priceList = product.getJsonArray(Product.PRICES).getList();
                                final Object productId = product.getValue(Product.ID);

                                final JsonObject prod = includeExcludeTransformation
                                    .transform(product)
                                    .put(User.UPDATED_BY, user.getValue(User.ID))
                                    .put(User.UPDATE_DATE, Converters.toMySqlDateString(new Date()));

                                final List<JsonObject> newProductPriceList = priceList.stream()
                                    .map(removeNullsTransformation::transform)
                                    .map(unitConverterTransformation::transform)
                                    .map(unitIncludeExcludeTransformation::transform)
                                    .map(js ->
                                        js
                                            .put(ProductUnitPrice.PRODUCT_ID, productId)
                                            .put(Unit.CREATED_BY, 0)
                                            .put(Unit.UPDATED_BY, 0))
                                    .collect(Collectors.toList());

                                Promises
                                    .when(
                                        WebUtils.update(TABLE_NAME,
                                            prod,
                                            new JsonObject()
                                                .put(Product.ID, productId), con)
                                            .map(updateResult -> productId),
                                        WebUtils.createMulti(PRODUCT_UNIT_PRICES_TABLE,
                                            newProductPriceList, con)
                                    )
                                    .mapToPromise(t -> {
                                        Defer<Void> defer = Promises.defer();
                                        con.commit(Util.makeDeferred(defer));
                                        return defer.promise()
                                            .map(v -> (MutableTpl2<Object, UpdateResult>) t);
                                    })
                                    .then(tpl2 -> tpl2.accept(
                                        (id, list) -> message.reply(id)))
                                    .then(e -> vertx.eventBus().publish(
                                        UmEvents.PRODUCT_UPDATED,
                                        prod.put(User.UPDATED_BY, user)
                                            .put(Product.PRICES, newProductPriceList)))
                                    .error(e -> ExceptionUtil.fail(message, e))
                                    .complete(p -> con.close())
                                ;

                            } catch (Exception e) {
                                con.close();
                                ExceptionUtil.fail(message, e);
                            }
                        })
                        .error(e -> ExceptionUtil.fail(message, e))
                    ;
                })
            .error(e ->
                ExceptionUtil.fail(message, e));
    }

    public void delete(Message<Object> message) {

        final JsonObject user = new JsonObject(message.headers().get(AUTH_TOKEN));

        Promises.callable(() -> Converters.toLong(message.body()))
            .mapToPromise(id -> WebUtils.delete(TABLE_NAME, id, jdbcClient)
                .map(updateResult -> updateResult.getUpdated() > 0 ? id : 0)
                .then(message::reply))
            .then(v -> vertx.eventBus().publish(UmEvents.PRODUCT_DELETED,
                new JsonObject()
                    .put(Inventory.DELETED_BY, user)
                    .put(Inventory.DELETE_DATE, Converters.toMySqlDateString(new Date()))))
            .error(e ->
                ExceptionUtil.fail(message, e))
        ;
    }

    public void findDecomposed(Message<Object> message) {

        final String pSel = productFields.stream().map(field -> "p." + field).collect(Collectors.joining(", "));
        final String prSel = productUnitPriceFields.stream().map(field -> "up." + field).collect(Collectors.joining(", "));

        Promises
            .callable(message::body)
            .then(
                id -> WebUtils.query("select " + pSel + ", " + prSel + " " +
                    "from " + TABLE_NAME + " p " +
                    "join " + Tables.productUnitPrices + " up on up.productId = p.id " +
                    "where p.id = " + id, jdbcClient)
                    .decide(resultSet -> resultSet.getNumRows() < 1 ? PRODUCT_NOT_FOUND : Decision.CONTINUE)
                    .on(PRODUCT_NOT_FOUND,
                        rs ->
                            message.reply(
                                new JsonObject()
                                    .put(Services.RESPONSE_CODE, UmErrorCodes.PRODUCT_NOT_FOUND.code())
                                    .put(Services.MESSAGE_CODE, UmErrorCodes.PRODUCT_NOT_FOUND.messageCode())
                                    .put(Services.MESSAGE,
                                        Um.messageBundle.translate(
                                            UmErrorCodes.PRODUCT_NOT_FOUND.messageCode(),
                                            new JsonObject()
                                                .put(Product.ID, id))),
                                new DeliveryOptions()
                                    .addHeader(Services.RESPONSE_CODE,
                                        Util.toString(UmErrorCodes.PRODUCT_NOT_FOUND.code()))
                            ))
                    .contnue(
                        rs -> Promises.from(rs)
                            .map(rset -> {
                                JsonObject product = new JsonObject();
                                ImmutableList.Builder<JsonObject> builder = ImmutableList.builder();

                                List<JsonArray> results = rs.getResults();
                                JsonArray array = results.get(0);

                                for (int i = 0; i < productFields.size(); i++) {
                                    product.put(productFields.get(i), array.getValue(i));
                                }

                                final int length = productFields.size() + productUnitPriceFields.size();

                                results.forEach(jsonArray -> {

                                    JsonObject productUnitPrice = new JsonObject();

                                    for (int i = productFields.size(); i < length; i++) {
                                        productUnitPrice.put(productUnitPriceFields.get(i - productFields.size()),
                                            jsonArray.getValue(i));
                                    }

                                    builder.add(productUnitPrice);
                                });

                                return product.put(Product.PRICES, builder.build());
                            })
                            .then(p -> {
                                System.out.println(p.encodePrettily());
                            })
                            .then(message::reply))
                    .error(e -> ExceptionUtil.fail(message, e))
            )
            .error(e -> ExceptionUtil.fail(message, e))
        ;

    }

    public void findAllDecomposed(Message<JsonObject> message) {
        Promises.from(message.body() == null ? new JsonObject() : message.body())
            .map(removeNullsTransformation::transform)
            .mapToPromise(params -> {

                final JsonArray prmsArray = new JsonArray();

                String where;

                {
                    String whr = params.fieldNames()
                        .stream()
                        .peek(nm -> prmsArray.add(params.getValue(nm)))
                        .map(nm -> nm + " = ?")
                        .collect(Collectors.joining(" and "));
                    where = whr.isEmpty() ? "" : "where " + whr;
                }

                return WebUtils.query("select * from products " + where +
                    " order by name asc", prmsArray, jdbcClient);
            })
            .map(ResultSet::getRows)
            .then(
                list ->
                    message.reply(
                        new JsonObject()
                            .put(DATA, list)
                    ))
            .error(e -> ExceptionUtil.fail(message, e))
        ;
    }

    public void unitWisePrice(Message<JsonObject> message) {
        try {
            final String[] fields = new String[]{"productId", "unitId", "price"};

            final String fieldStr = String.join(", ", fields);

            WebUtils.query(
                "select " + fieldStr + " from " + Tables.productUnitPrices +
                    " group by " + fieldStr, jdbcClient)
                .map(rs -> rs.getResults())
                .map(jsonArrays -> {

                    final JsonObject map = new JsonObject();

                    jsonArrays.forEach(jsonArray -> {

                        final String productId = jsonArray.getValue(0).toString();
                        String unitId = jsonArray.getValue(1).toString();
                        Double price = jsonArray.getDouble(2);

                        JsonObject unitJson = map.getJsonObject(productId);

                        if (unitJson == null) {
                            unitJson = new JsonObject();
                            map.put(productId, unitJson);
                        }

                        unitJson.put(unitId, price);
                    });

                    return map;
                })
                .then(message::reply)
                .error(e -> ExceptionUtil.fail(message, e))
            ;
        } catch (Exception ex) {
            ExceptionUtil.fail(message, ex);
            LOGGER.error("ERROR IN unitWisePrice", ex);
        }
    }
}
