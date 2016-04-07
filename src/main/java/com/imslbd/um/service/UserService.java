package com.imslbd.um.service;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.imslbd.call_center.MyApp;
import com.imslbd.um.Tables;
import com.imslbd.um.Um;
import com.imslbd.um.UmErrorCodes;
import com.imslbd.um.UmUtils;
import com.imslbd.um.model.Unit;
import com.imslbd.um.model.User;
import io.crm.ErrorCodes;
import io.crm.pipelines.transformation.JsonTransformationPipeline;
import io.crm.pipelines.transformation.impl.json.object.ConverterTransformation;
import io.crm.pipelines.transformation.impl.json.object.DefaultValueTransformation;
import io.crm.pipelines.transformation.impl.json.object.IncludeExcludeTransformation;
import io.crm.pipelines.transformation.impl.json.object.RemoveNullsTransformation;
import io.crm.pipelines.validator.ValidationPipeline;
import io.crm.pipelines.validator.ValidationResult;
import io.crm.pipelines.validator.Validator;
import io.crm.pipelines.validator.composer.JsonObjectValidatorComposer;
import io.crm.promise.Decision;
import io.crm.promise.Promises;
import io.crm.util.ExceptionUtil;
import io.crm.util.Util;
import io.crm.web.util.Converters;
import io.crm.web.util.Pagination;
import io.crm.web.util.WebUtils;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by shahadat on 3/6/16.
 */
public class UserService {
    public static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);
    private static final java.lang.String SIZE = "size";
    private static final String PAGE = "page";
    private static final String HEADERS = "headers";
    private static final String PAGINATION = "pagination";
    private static final String DATA = "data";
    private static final Integer DEFAULT_PAGE_SIZE = 100;
    private static final String VALIDATION_ERROR = "validationError";

    private final Vertx vertx;
    private final JDBCClient jdbcClient;
    private final RemoveNullsTransformation removeNullsTransformation;
    private final DefaultValueTransformation defaultValueTransformationParams = new DefaultValueTransformation(Util.EMPTY_JSON_OBJECT);

    private static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    private final IncludeExcludeTransformation includeExcludeTransformation;
    private final ConverterTransformation converterTransformation;
    private final DefaultValueTransformation defaultValueTransformation;

    private final JsonTransformationPipeline transformationPipeline;
    private final ValidationPipeline<JsonObject> validationPipeline;

    public UserService(JDBCClient jdbcClient, String[] fields, Vertx vertx) {
        this.vertx = vertx;
        this.jdbcClient = jdbcClient;

        removeNullsTransformation = new RemoveNullsTransformation();
        includeExcludeTransformation = new IncludeExcludeTransformation(
            ImmutableSet.copyOf(Arrays.asList(fields)), null);
        converterTransformation = new ConverterTransformation(converters(fields));

        defaultValueTransformation = new DefaultValueTransformation(
            new JsonObject()
                .put(Unit.UPDATED_BY, 0)
                .put(Unit.CREATED_BY, 0)
        );

        transformationPipeline = new JsonTransformationPipeline(
            ImmutableList.of(
                removeNullsTransformation,
                includeExcludeTransformation,
                converterTransformation,
                defaultValueTransformation
            )
        );

        validationPipeline = new ValidationPipeline<>(ImmutableList.copyOf(validators()));
    }

    private List<Validator<JsonObject>> validators() {
        List<Validator<JsonObject>> validators = new ArrayList<>();
        JsonObjectValidatorComposer validatorComposer = new JsonObjectValidatorComposer(validators, Um.messageBundle)
            .field(User.USER_ID,
                fieldValidatorComposer -> fieldValidatorComposer
                    .stringType()
                    .notNullEmptyOrWhiteSpace())
            .field(User.USERNAME,
                fieldValidatorComposer -> fieldValidatorComposer
                    .notNullEmptyOrWhiteSpace()
                    .stringType())
            .field(User.PASSWORD,
                fieldValidatorComposer -> fieldValidatorComposer
                    .notNullEmptyOrWhiteSpace()
                    .stringType())
            .field(User.NAME,
                fieldValidatorComposer -> fieldValidatorComposer
                    .notNullEmptyOrWhiteSpace()
                    .stringType())
            .field(User.PHONE,
                fieldValidatorComposer -> fieldValidatorComposer
                    .notNullEmptyOrWhiteSpace()
                    .stringType());
        return validatorComposer.getValidatorList();
    }

    private ImmutableMap<String, Function<Object, Object>> converters(String[] fields) {
        JsonObject db = MyApp.loadConfig().getJsonObject(Services.DATABASE);
        String url = db.getString("url");
        String user = db.getString("user");
        String password = db.getString("password");

        ImmutableMap.Builder<String, Function<Object, Object>> builder = ImmutableMap.builder();
        try {
            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                Statement statement = connection.createStatement();
                statement.execute("select * from " + Tables.users.name());
                ResultSet rs = statement.getResultSet();
                ResultSetMetaData metaData = rs.getMetaData();

                int columnCount = metaData.getColumnCount();
                for (int i = 0; i < columnCount; i++) {
                    int columnType = metaData.getColumnType(i + 1);
                    Function<Object, Object> converter = Services.TYPE_CONVERTERS.get(columnType);
                    Objects.requireNonNull(converter, "Type Converter can't be null for Type: " +
                        "[" + columnType + ": " + Services.JDBC_TYPES.get(columnType) + "]");
                    builder.put(fields[i], converter);
                    System.out.println(columnType + ": " + Services.JDBC_TYPES.get(columnType));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Error connecting to database through jdbc", e);
        }

        return builder.build();
    }

    public void findAll(Message<JsonObject> message) {
        Promises.from(message.body())
            .map(defaultValueTransformationParams::transform)
            .map(removeNullsTransformation::transform)
            .then(json -> {
                int page = json.getInteger(PAGE, 1);
                int size = json.getInteger(SIZE, DEFAULT_PAGE_SIZE);
                String from = "from users";

                Promises.when(
                    WebUtils.query("select count(*) as totalCount " + from, jdbcClient)
                        .map(resultSet -> resultSet.getResults().get(0).getLong(0)),
                    WebUtils.query(
                        "select * " + from + " "
                            + UmUtils.limitOffset(page, size), jdbcClient)
                        .map(resultSet3 -> new JsonObject()
                            .put(HEADERS, resultSet3.getColumnNames()
                                .stream()
                                .map(WebUtils::describeField)
                                .collect(Collectors.toList()))
                            .put(DATA, resultSet3.getRows())))
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
        Promises
            .callable(() -> message.body())
            .then(
                id -> WebUtils.query("select * from users where id = " + id, jdbcClient)
                    .decide(resultSet -> resultSet.getNumRows() < 1 ? USER_NOT_FOUND : Decision.OTHERWISE)
                    .on(USER_NOT_FOUND,
                        rs -> {
                            message.reply(
                                new JsonObject()
                                    .put(Services.RESPONSE_CODE, UmErrorCodes.USER_NOT_FOUND.code())
                                    .put(Services.MESSAGE_CODE, UmErrorCodes.USER_NOT_FOUND.messageCode())
                                    .put(Services.MESSAGE,
                                        Um.messageBundle.translate(
                                            UmErrorCodes.USER_NOT_FOUND.messageCode(),
                                            new JsonObject()
                                                .put(User.ID, id))),
                                new DeliveryOptions()
                                    .addHeader(Services.RESPONSE_CODE, Util.toString(UmErrorCodes.USER_NOT_FOUND.code()))
                            );
                        })
                    .otherwise(
                        rs -> Promises.from(rs)
                            .map(rset -> rset.getRows().get(0))
                            .map(user -> {
                                user.remove(User.PASSWORD);
                                return user;
                            })
                            .then(message::reply))
                    .error(e -> ExceptionUtil.fail(message, e))
            )
            .error(e -> ExceptionUtil.fail(message, e))
        ;
    }

    public void create(Message<JsonObject> message) {
        System.out.println();
        Promises.callable(() -> transformationPipeline.transform(message.body()))
            .decideAndMap(
                user -> {
                    List<ValidationResult> validationResults = validationPipeline.validate(user);
                    return validationResults != null ? Decision.of(VALIDATION_ERROR, validationResults) : Decision.of(Decision.OTHERWISE, user);
                })
            .on(VALIDATION_ERROR,
                rsp -> {
                    List<ValidationResult> validationResults = (List<ValidationResult>) rsp;
                    System.out.println("REPLYING: " + validationResults);
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
            .otherwise(
                rsp -> {
                    JsonObject user = (JsonObject) rsp;
                    WebUtils
                        .create(Tables.users.name(), user, jdbcClient)
                        .map(updateResult -> updateResult.getKeys().getLong(0))
                        .then(message::reply)
                        .error(e -> ExceptionUtil.fail(message, e));
                })
            .error(e ->
                ExceptionUtil.fail(message, e));
    }

    public void update(Message<JsonObject> message) {
        Promises.callable(() -> transformationPipeline.transform(message.body()))
            .decideAndMap(
                user -> {
                    List<ValidationResult> validationResults = validationPipeline.validate(user);
                    return validationResults != null ? Decision.of(VALIDATION_ERROR, validationResults) : Decision.of(Decision.OTHERWISE, user);
                })
            .on(VALIDATION_ERROR,
                rsp -> {
                    List<ValidationResult> validationResults = (List<ValidationResult>) rsp;
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
            .otherwise(
                rsp -> {
                    JsonObject user = (JsonObject) rsp;
                    final Long id = user.getLong(User.ID, 0L);
                    WebUtils.update(Tables.users.name(), user, id, jdbcClient)
                        .map(updateResult -> updateResult.getUpdated() > 0 ? id : 0)
                        .then(message::reply)
                        .error(e -> ExceptionUtil.fail(message, e));
                })
            .error(e -> ExceptionUtil.fail(message, e));
    }

    public void delete(Message<Object> message) {
        Promises.callable(() -> Converters.toLong(message.body()))
            .mapToPromise(id -> WebUtils.delete(Tables.users.name(), id, jdbcClient)
                .map(updateResult -> updateResult.getUpdated() > 0 ? id : 0)
                .then(message::reply))
            .error(e ->
                ExceptionUtil.fail(message, e))
        ;
    }

    public static void main(String... args) {
        SecureRandom secureRandom = new SecureRandom();
        String substring = (secureRandom.nextLong() + "").substring(9);
        System.out.println(substring);
        System.out.println(substring.length());
    }
}
