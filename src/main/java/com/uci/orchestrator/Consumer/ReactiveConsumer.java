package com.uci.orchestrator.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inversoft.error.Errors;
import com.inversoft.rest.ClientResponse;
import com.uci.dao.models.XMessageDAO;
import com.uci.dao.repository.XMessageRepository;
import com.uci.utils.BotService;
import com.uci.utils.bot.util.BotUtil;
import com.uci.utils.cache.service.RedisCacheService;
import com.uci.utils.encryption.AESWrapper;
import com.uci.utils.kafka.ReactiveProducer;
import com.uci.utils.kafka.SimpleProducer;
import com.uci.utils.service.UserService;

import io.fusionauth.domain.api.UserRequest;
import io.fusionauth.domain.api.UserResponse;
import io.fusionauth.domain.User;
import io.fusionauth.domain.UserRegistration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.DeviceType;
import messagerosa.core.model.SenderReceiverInfo;
import messagerosa.core.model.Transformer;
import messagerosa.core.model.XMessage;
import messagerosa.xml.XMessageParser;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import javax.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.uci.utils.encryption.AESWrapper.encodeKey;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReactiveConsumer {

    private final Flux<ReceiverRecord<String, String>> reactiveKafkaReceiver;

    @Autowired
    public XMessageRepository xMessageRepository;

    @Autowired
    public SimpleProducer kafkaProducer;

    @Autowired
    public ReactiveProducer reactiveProducer;

    @Value("${odk-transformer}")
    public String odkTransformerTopic;

    @Value("${broadcast-transformer}")
    public String broadcastTransformerTopic;

    @Value("${generic-transformer}")
    public String genericTransformerTopic;

    @Autowired
    public BotService botService;

    @Autowired
    private UserService userService;

    @Value("${encryptionKeyString}")
    private String secret;

    @Autowired
    private RedisCacheService redisCacheService;

    public AESWrapper encryptor;

    private final String DEFAULT_APP_NAME = "Global Bot";
    LocalDateTime yesterday = LocalDateTime.now().minusDays(1L);

    @Value("${broadcastNotificationChunkSize}")
    private String broadcastNotificationChunkSize;

    private long notificationProcessedCount;

    private long consumeCount;
    private long pushCount;

    private HashSet<String> federatedUsers = new HashSet<>();

    private long insertSetCount, existSetCount, existingFederatedUsers;
    @Value("${notification-kafka-cache}")
    private String notificationKafkaCache;

    @KafkaListener(id = "${inboundProcessed}", topics = "${inboundProcessed}", properties = {"spring.json.value.default.type=java.lang.String"})
    public void onMessage(@Payload String stringMessage) {
        try {
            final long startTime = System.nanoTime();
            logTimeTaken(startTime, 0, null);
            XMessage msg = XMessageParser.parse(new ByteArrayInputStream(stringMessage.getBytes()));
            if (msg != null && msg.getProvider().equalsIgnoreCase("firebase")) {
                consumeCount++;
                log.info("Consume topic by Orchestrator count : " + consumeCount);

                /**
                 *  Kafka Duplication check
                 *  If messageId found in cache we don't send notifications
                 *  We need to resolve this issue from kafka side this is temporary solution
                 */
                if (msg.getMessageId() != null && msg.getMessageId().getChannelMessageId() != null && notificationKafkaCache != null && !notificationKafkaCache.isEmpty()) {
                    String messageId = msg.getMessageId().getChannelMessageId();

                    if (!redisCacheService.isKeyExists(notificationKafkaCache)) {
                        Set<String> messageIdSet = new HashSet<>();
                        messageIdSet.add(messageId);
                        redisCacheService.setCache(notificationKafkaCache, messageIdSet);
                        insertSetCount++;
                        log.info("ReactiveConsumer:First MessageId Insert in Redis count : " + insertSetCount + " MessageId : " + messageId);
                    } else {
                        Set<String> messageIdSet = (Set<String>) redisCacheService.getCache(notificationKafkaCache);
                        if (messageIdSet.contains(messageId)) {
                            existSetCount++;
                            log.info("ReactiveConsumer:Already Consumed : " + existSetCount + " MessageId : " + messageId);
                            return;
                        } else {
                            messageIdSet.add(messageId);
                            redisCacheService.setCache(notificationKafkaCache, messageIdSet);
                            insertSetCount++;
                            log.info("ReactiveConsumer:MessageId Insert in Redis count : " + insertSetCount + " MessageId : " + messageId);
                        }
                    }
                } else {
                    log.error("ReactiveConsumer:MessageId Not Found : " + msg.getMessageId() + " or Notification Cache Name Empty : " + notificationKafkaCache);
                }

                // This code for kafka duplication problem
//                if (msg.getMessageId() != null && msg.getMessageId().getChannelMessageId() != null) {
//                    String messageId = msg.getMessageId().getChannelMessageId();
//                    if (messageIdSet.contains(messageId)) {
//                        existSetCount++;
//                        log.info("ReactiveConsumer:Already Counsumed : " + existSetCount + " MessageId : " + messageId);
//                        return;
//                    } else {
//                        insertSetCount++;
//                        log.info("ReactiveConsumer:Insert in set count : " + insertSetCount + " MessageId : " + messageId);
//                        messageIdSet.add(messageId);
//                    }
//                }
//                log.info("ReactiveConsumer: Total MessageId Set : " + messageIdSet.size());
            }

            SenderReceiverInfo from = msg.getFrom();
            logTimeTaken(startTime, 1, null);
            botService.getBotNodeFromName(msg.getApp()).doOnNext(new Consumer<JsonNode>() {
                @Override
                public void accept(JsonNode botNode) {
                    if (botNode != null && !botNode.isEmpty()) {
                        from.setCampaignID(msg.getApp());
                        if (from.getDeviceType() == null) {
                            from.setDeviceType(DeviceType.PHONE);
                        }
                        if (msg.getAdapterId() == null || msg.getAdapterId().isEmpty()) {
                            msg.setAdapterId(BotUtil.getBotNodeAdapterId(botNode));
                        }
                        /* Set XMessage Transformers */
                        XMessage message = setXMessageTransformers(msg, botNode);

                        String appId = botNode.get("id").asText();
                        JsonNode firstTransformer = botNode.findValues("transformers").get(0);

                        if (message != null && message.getProviderURI().equals("firebase") && message.getChannelURI().equals("web")) {
                            if (firstTransformer.findValue("type") != null && firstTransformer.findValue("type").asText().equals(BotUtil.transformerTypeBroadcast)) {
                                try {
                                    log.info("ReactiveConsumer:broadcastNotificationChunkSize : " + broadcastNotificationChunkSize);
                                    /* Switch From & To */
                                    switchFromTo(msg);
                                    Integer chunkSize = null;
                                    try {
                                        chunkSize = Integer.parseInt(broadcastNotificationChunkSize);
                                    } catch (NumberFormatException ex) {
                                        chunkSize = null;
                                    }
                                    if (chunkSize != null) {
                                        if (msg.getTransformers() != null && msg.getTransformers().size() > 0 && msg.getTransformers().get(0) != null
                                                && msg.getTransformers().get(0).getMetaData() != null && msg.getTransformers().get(0).getMetaData().get("federatedUsers") != null) {
                                            JSONArray federatedUsers = new JSONObject(msg.getTransformers().get(0).getMetaData().get("federatedUsers")).getJSONArray("list");
                                            int totalFederatedUsers = federatedUsers.length();
                                            if (totalFederatedUsers <= chunkSize) {
                                                log.info("ReactiveConsumer:Pushed Federated Users to Kafka Topic: " + totalFederatedUsers);
                                                kafkaProducer.send(broadcastTransformerTopic, msg.toXML());
                                            } else {
                                                List<JSONArray> jsonArrayList = chunkArrayList(federatedUsers, chunkSize);
                                                int count = 1;
                                                for (JSONArray jsonArray : jsonArrayList) {
                                                    log.info("Total Federated Users : " + federatedUsers.length() + " Chunk size : " + jsonArray.length() + " Sent to kafka : " + count);
                                                    msg.getTransformers().get(0).getMetaData().put("federatedUsers", new JSONObject().put("list", jsonArray).toString());
                                                    log.info("ReactiveConsumer:Pushed Federated Users to Kafka Topic: " + jsonArray.length());
                                                    kafkaProducer.send(broadcastTransformerTopic, msg.toXML());
                                                    count++;
                                                }
                                            }
                                        } else {
                                            log.error("ReactiveConsumer:federatedUsers not found in xMessage: " + msg);
                                        }
                                    } else {
                                        if (msg.getTransformers() != null && msg.getTransformers().size() > 0 && msg.getTransformers().get(0) != null
                                                && msg.getTransformers().get(0).getMetaData() != null && msg.getTransformers().get(0).getMetaData().get("federatedUsers") != null) {
                                            JSONArray federatedUsers = new JSONObject(msg.getTransformers().get(0).getMetaData().get("federatedUsers")).getJSONArray("list");
                                            log.info("ReactiveConsumer:Pushed Federated Users to Kafka Topic: " + federatedUsers.length());
                                            kafkaProducer.send(broadcastTransformerTopic, msg.toXML());
                                        } else {
                                            log.error("ReactiveConsumer:federatedUsers not found in xMessage: " + msg);
                                        }
                                    }
                                    pushCount++;
                                    notificationProcessedCount++;
                                    logTimeTaken(startTime, 0, "Notification processed by orchestrator: " + notificationProcessedCount + " :: Push count : "
                                            + pushCount + " :: orchestrator-notification-process-end-time: %d ms");
                                } catch (Exception ex) {
                                    log.error("ReactiveConsumer:Notification Triggering Process:Error in pushing xMessage to kafka: " + ex.getMessage());
                                }
                            } else {
                                log.error("ReactiveConsumer:onMessage:: Invalid Type Found : " + firstTransformer.findValue("type"));
                            }
                        } else {
                            log.info("ReactiveConsumer:ResolveUser Calling: " + message);
                            resolveUser(message, appId)
                                    .doOnNext(new Consumer<XMessage>() {
                                        @Override
                                        public void accept(XMessage msg) {
                                            SenderReceiverInfo from = msg.getFrom();
                                            // msg.setFrom(from);
                                            try {
//                                                getLastMessageID(msg)
//                                                        .doOnNext(lastMessageID -> {
                                                logTimeTaken(startTime, 4, null);
//                                                msg.setLastMessageID(lastMessageID);

                                                /* Switch From & To */
                                                switchFromTo(msg);

                                                if (msg.getMessageState().equals(XMessage.MessageState.REPLIED) || msg.getMessageState().equals(XMessage.MessageState.OPTED_IN)) {
                                                    try {
                                                        log.info("final msg.toXML(): " + msg.toXML().toString());
                                                        if (firstTransformer.findValue("type") != null && firstTransformer.findValue("type").asText().equals("generic")) {
                                                            kafkaProducer.send(genericTransformerTopic, msg.toXML());
                                                        } else {
                                                            kafkaProducer.send(odkTransformerTopic, msg.toXML());
                                                        }
                                                        // reactiveProducer.sendMessages(odkTransformerTopic, msg.toXML());
                                                    } catch (JAXBException e) {
                                                        e.printStackTrace();
                                                    }
                                                    logTimeTaken(startTime, 0, "process-end: %d ms");
                                                } else {
                                                    log.error("ReactiveConsumer:onMessage:: MessageSate Invalid Found : " + msg.getMessageState());
                                                }
//                                                        })
//                                                        .doOnError(new Consumer<Throwable>() {
//                                                            @Override
//                                                            public void accept(Throwable throwable) {
//                                                                log.error("Error in getLastMessageID" + throwable.getMessage());
//                                                            }
//                                                        })
//                                                        .subscribe();
                                            } catch (Exception ex) {
                                                log.error("ReactiveConsumer:ODK and Generic Bot Processing:Exception: " + ex.getMessage());
                                            }

                                        }
                                    })
                                    .doOnError(new Consumer<Throwable>() {
                                        @Override
                                        public void accept(Throwable throwable) {
                                            log.error("Error in resolveUser" + throwable.getMessage());
                                        }
                                    }).subscribe();
                        }
                    } else {
                        log.error("Bot node not found by name: " + msg.getApp());
                    }

                }

            }).subscribe();
        } catch (Exception e) {
            log.error("An Error ReactiveConsumer : " + e.getMessage());
        }
    }

    /**
     * Set Transformer in XMessage with transformer required data in meta
     *
     * @param xMessage
     * @param botNode
     * @return XMessage
     */
    private XMessage setXMessageTransformers(XMessage xMessage, JsonNode botNode) {
        try {
            ArrayList<Transformer> transformers = new ArrayList<Transformer>();

            ArrayList transformerList = (ArrayList) botNode.findValues("transformers");
            transformerList.forEach(transformerTmp -> {
                JsonNode transformerNode = (JsonNode) transformerTmp;
                int i = 0;
                while (transformerNode.get(i) != null && transformerNode.get(i).path("meta") != null) {
                    JsonNode transformer = transformerNode.get(i);
                    JsonNode transformerMeta = transformer.path("meta") != null
                            ? transformer.path("meta") : null;
                    log.info("transformer:" + transformer);

                    HashMap<String, String> metaData = new HashMap<String, String>();
                    /* Bot Data */
                    metaData.put("startingMessage", BotUtil.getBotNodeData(botNode, "startingMessage"));
                    metaData.put("botId", BotUtil.getBotNodeData(botNode, "id"));
                    metaData.put("botOwnerID", BotUtil.getBotNodeData(botNode, "ownerID"));
                    metaData.put("botOwnerOrgID", BotUtil.getBotNodeData(botNode, "ownerOrgID"));

                    /* Transformer Data */
                    metaData.put("id", transformer.get("id").asText());
                    metaData.put("type", transformerMeta.get("type") != null
                            && !transformerMeta.get("type").asText().isEmpty()
                            ? transformerMeta.get("type").asText()
                            : "");
                    metaData.put("formID", transformerMeta.findValue("formID") != null
                            && !transformerMeta.findValue("formID").asText().isEmpty()
                            ? transformerMeta.findValue("formID").asText()
                            : "");
                    if (transformerMeta.get("type") != null && transformerMeta.get("type").asText().equals(BotUtil.transformerTypeBroadcast)) {
                        if (xMessage != null && xMessage.getFrom() != null && xMessage.getFrom().getMeta() != null && xMessage.getFrom().getMeta().containsKey("page")) {
                            log.info("page number orch : " + xMessage.getFrom().getMeta().get("page"));
                            metaData.put("federatedUsers", getFederatedUsersMeta(botNode, transformer, xMessage.getFrom().getMeta()));
                        } else {
                            metaData.put("federatedUsers", getFederatedUsersMeta(botNode, transformer, xMessage.getFrom().getMeta()));
                        }
                    }

                    if (transformerMeta.findValue("hiddenFields") != null && !transformerMeta.findValue("hiddenFields").isEmpty()) {
                        metaData.put("hiddenFields", transformerMeta.findValue("hiddenFields").toString());
                    }

                    if (transformer.findValue("serviceClass") != null && !transformer.findValue("serviceClass").asText().isEmpty()) {
                        String serviceClass = transformer.findValue("serviceClass").toString();
                        if (serviceClass != null && !serviceClass.isEmpty() && serviceClass.contains("\"")) {
                            serviceClass = serviceClass.replaceAll("\"", "");
                        }
                        metaData.put("serviceClass", serviceClass);
                    }

                    if (transformerMeta.get("templateId") != null && !transformerMeta.get("templateId").asText().isEmpty()) {
                        metaData.put("templateId", transformerMeta.get("templateId").asText());
                    }

                    if (transformerMeta.get("title") != null && !transformerMeta.get("title").asText().isEmpty()) {
                        metaData.put("title", transformerMeta.get("title").asText());
                    }

                    if (transformer.get("type") != null && transformer.get("type").asText().equals(BotUtil.transformerTypeGeneric)) {
                        metaData.put("url", transformer.findValue("url").asText());
                    }

                    Transformer transf = new Transformer();
                    transf.setId(transformer.get("id").asText());
                    transf.setMetaData(metaData);

                    transformers.add(transf);
                    i++;
                }
            });
            xMessage.setTransformers(transformers);
            return xMessage;
        } catch (Exception ex) {
            log.error("Error:setXMessageTransformers::Exception:" + ex.getMessage());
            return null;
        }
    }

    /**
     * Get Federated Users Data for Broadcast transformer
     *
     * @param botNode
     * @param transformer
     * @return Federated users as json string
     */
    private String getFederatedUsersMeta(JsonNode botNode, JsonNode transformer, Map<String, String> meta) {
        String botId = botNode.get("id").asText();

        /* Get federated users from federation services */
        JSONArray users = userService.getUsersFromFederatedServers(botId, meta);
        for (int i = 0; i < users.length(); i++) {
            JSONObject jsonObject = (JSONObject) users.get(i);
            if (jsonObject != null && !jsonObject.isNull("phoneNo")) {
                String phoneNo = jsonObject.getString("phoneNo");
                if (federatedUsers.contains(phoneNo)) {
                    existingFederatedUsers++;
                    log.info("ReactiveConsumer:getFederatedUsersMeta:: Duplicate Phone Number found : count: " + existingFederatedUsers + " Phone No : " + phoneNo);
                }
//                else {
//                    log.info("ReactiveConsumer:getFederatedUsersMeta::Inserting User in set : " + phoneNo);
//                    federatedUsers.add(phoneNo);
//                }
            }
//            else {
//                log.error("ReactiveConsumer:getFederatedUsersMeta::No Federated Users Found: " + users.get(i).toString());
//            }
        }

        log.info("ReactiveConsumer:getFederatedUsersMeta::Count: " + (users == null ? "user not found" : users.length()) + " >> Set count: " + federatedUsers.size());

        /* Check if users, & related meta data exists in transformer */
        if (users != null && transformer.get("meta") != null
                && transformer.get("meta").get("templateType") != null
                && transformer.get("meta").get("body") != null) {
            ObjectNode transformerMeta = (ObjectNode) transformer.get("meta");

            /* Create request body data for user template message */
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("body", transformerMeta.get("body").asText());
            node.put("type", transformerMeta.get("templateType").asText());

            ArrayNode sampleData = mapper.createArrayNode();
            for (int i = 0; i < users.length(); i++) {
                ObjectNode userData = mapper.createObjectNode();
                if (transformerMeta.get("params") != null && !transformerMeta.get("params").toString().isEmpty()) {
                    JSONArray paramArr = new JSONArray(transformerMeta.get("params").toString());
                    for (int k = 0; k < paramArr.length(); k++) {
                        if (!((JSONObject) users.get(i)).isNull(paramArr.getString(k))) {
                            userData.put(paramArr.getString(k), ((JSONObject) users.get(i)).getString(paramArr.getString(k)));
                        }
                    }
                }
                userData.put("__index", i);
                sampleData.add(userData);
            }
            node.put("sampleData", sampleData);

            /* Fetch user messages by template from template service */
            ArrayList<JSONObject> usersMessage = userService.getUsersMessageByTemplate(node);

//            for (int i = 0; i < usersMessage.size(); i++) {
//                JSONObject jsonObject = usersMessage.get(i);
//                if(jsonObject != null && !jsonObject.isNull("")){
//
//                }
//            }

            log.info("ReactiveConsumer:getUsersMessageByTemplate::Count: " + usersMessage.size());

            /* Set User messages against the user phone */
            ObjectNode federatedUsersMeta = mapper.createObjectNode();
            ArrayNode userMetaData = mapper.createArrayNode();
            usersMessage.forEach(userMsg -> {
                int j = Integer.parseInt(userMsg.get("__index").toString());
                JSONObject userObj = ((JSONObject) users.get(j));
                String userPhone = userObj.getString("phoneNo");

                ObjectNode map = mapper.createObjectNode();
                map.put("phone", userPhone);
                map.put("message", userMsg.get("body").toString());
                try {
                    /* FCM Token */
                    if (userObj.get("fcmToken") != null) {
                        map.put("fcmToken", userObj.getString("fcmToken"));
                    }
                    /* FCM - If clickActionUrl found in userObj, use it, override previous one */
                    if (userObj.get("fcmClickActionUrl") != null) {
                        map.put("fcmClickActionUrl", userObj.getString("fcmClickActionUrl"));
                    }
                    if (transformerMeta.get("data") != null) {
                        map.put("data", transformerMeta.get("data"));
                    }
                } catch (Exception e) {
                    log.error("ErrorParsingUserObj:getFederatedUsersMeta::Exception: " + e.getMessage());
                }

                userMetaData.add(map);
                log.info("index: " + j + ", body: " + userMsg.get("body").toString() + ", phone:" + userPhone);
            });

            federatedUsersMeta.put("list", userMetaData);

            return federatedUsersMeta.toString();
        } else {
            log.error("ReactiveConsumer:getFederatedUsersMetaElse::Users not found");
        }
        return "";
    }

    /**
     * Resolve User - Fetch user if exists or register it in Fusion Auth Client
     *
     * @param xmsg
     * @param appId
     * @return
     */
    private Mono<XMessage> resolveUser(XMessage xmsg, String appId) {
        try {
            SenderReceiverInfo from = xmsg.getFrom();
            String appName = xmsg.getApp();
            Boolean found = false;

            UUID appID = UUID.fromString(appId);

            String deviceString = from.getDeviceType().toString() + ":" + from.getUserID();
            String encodedBase64Key = encodeKey(secret);
            String deviceID = AESWrapper.encrypt(deviceString, encodedBase64Key);
            log.info("ReactiveConsumer:resolveUser::Calling:deviceString: " + deviceString + ", encyprted deviceString: " + deviceID);
            String userID = getFAUserIdForApp(deviceID, appID);

            if (userID != null && !userID.isEmpty()) {
                log.info("ReactiveConsumer:resolveUser::Found FA user id: " + userID);
                from.setDeviceID(userID);
                from.setEncryptedDeviceID(deviceID);
                xmsg.setFrom(from);
                return Mono.just(xmsg);
            } else {
                return botService.updateUser(deviceString, appName)
                        .flatMap(new Function<Pair<Boolean, String>, Mono<XMessage>>() {
                            @Override
                            public Mono<XMessage> apply(Pair<Boolean, String> result) {
                                if (result.getLeft()) {
                                    from.setDeviceID(result.getRight());
                                    from.setEncryptedDeviceID(deviceID);
                                    xmsg.setFrom(from);
                                    ClientResponse<UserResponse, Errors> response = botService.fusionAuthClient.retrieveUserByUsername(deviceID);
                                    if (response.wasSuccessful() && isUserRegistered(response, appID)) {
                                        redisCacheService.setFAUserIDForAppCache(getFACacheName(deviceID, appID), response.successResponse.user.id.toString());
                                        return Mono.just(xmsg);
                                    } else {
                                        return Mono.just(xmsg);
                                    }
                                } else {
                                    xmsg.setFrom(null);
                                    return Mono.just(xmsg);
                                }
                            }
                        }).doOnError(new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) {
                                log.error("ReactiveConsumer:resolveUser::Calling update user: " + throwable.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("ReactiveConsumer:resolveUser::Error in resolving user: " + e.getMessage());
            xmsg.setFrom(null);
            return Mono.just(xmsg);
        }
    }

    /**
     * Get Fusion Auth User's UUID for App
     *
     * @param deviceID
     * @param appID
     * @return
     */
    private String getFAUserIdForApp(String deviceID, UUID appID) {
        String userID = null;

        Object result = redisCacheService.getFAUserIDForAppCache(getFACacheName(deviceID, appID));
        userID = result != null ? result.toString() : null;

        if (userID == null || userID.isEmpty()) {
            ClientResponse<UserResponse, Errors> response = botService.fusionAuthClient.retrieveUserByUsername(deviceID);

            if (response.wasSuccessful() && isUserRegistered(response, appID)) {
                userID = response.successResponse.user.id.toString();
                redisCacheService.setFAUserIDForAppCache(getFACacheName(deviceID, appID), userID);
            }
        }
        return userID;
    }

    /**
     * Check if FA user is registered for appid
     *
     * @param response
     * @param appID
     * @return
     */
    private Boolean isUserRegistered(ClientResponse<UserResponse, Errors> response, UUID appID) {
        List<UserRegistration> registrations = response.successResponse.user.getRegistrations();
        for (int i = 0; i < registrations.size(); i++) {
            if (registrations.get(i).applicationId.equals(appID)) {
                return true;
            }
        }
        return false;
    }

    private String getFACacheName(String deviceID, UUID appID) {
        return deviceID + "-" + appID.toString();
    }

    /**
     * Update fusion auth user data
     *
     * @param user
     */
    private void updateFAUser(User user) {
        System.out.println(user);
        UserRequest r = new UserRequest(user);

        ClientResponse<UserResponse, Errors> response = botService.fusionAuthClient.updateUser(user.id, r);
        if (response.wasSuccessful()) {
            System.out.println("user update success");
        } else {
            System.out.println("error in user update" + response.errorResponse);
        }
    }

    /**
     * Log time taken between two checkpoints
     *
     * @param startTime
     * @param checkpointID
     */
    private void logTimeTaken(long startTime, int checkpointID, String formatedMsg) {
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        if (formatedMsg == null) {
            log.info(String.format("CP-%d: %d ms", checkpointID, duration));
        } else {
            log.info(String.format(formatedMsg, duration));
        }
    }

    /**
     * Get Last XMessage ID of user
     *
     * @param msg
     * @return
     */
    private Mono<String> getLastMessageID(XMessage msg) {
        if (msg != null && msg.getFrom() != null && msg.getFrom().getUserID() != null && msg.getMessageType().toString().equalsIgnoreCase("text")) {
            return getLatestXMessage(msg.getFrom().getUserID(), yesterday, "SENT").map(new Function<XMessageDAO, String>() {
                @Override
                public String apply(XMessageDAO msg1) {
                    if (msg1.getId() == null) {
                        System.out.println("cError");
                        return "";
                    }
                    return String.valueOf(msg1.getId());
                }
            });

        } else if (msg != null && msg.getFrom() != null && msg.getFrom().getUserID() != null && msg.getMessageType().toString().equalsIgnoreCase("button")) {
            return getLatestXMessage(msg.getFrom().getUserID(), yesterday, "SENT").map(new Function<XMessageDAO, String>() {
                @Override
                public String apply(XMessageDAO lastMessage) {
                    return String.valueOf(lastMessage.getId());
                }
            });
        } else {
            log.error("UserId not found : " + msg.toString());
        }
        return Mono.empty();
    }

    /**
     * Get Latest XMessage of a user
     *
     * @param userID
     * @param yesterday
     * @param messageState
     * @return
     */
    private Mono<XMessageDAO> getLatestXMessage(String userID, LocalDateTime yesterday, String messageState) {
        return xMessageRepository
                .findAllByUserIdAndTimestampAfter(userID, yesterday).collectList()
                .map(new Function<List<XMessageDAO>, XMessageDAO>() {
                    @Override
                    public XMessageDAO apply(List<XMessageDAO> xMessageDAOS) {
                        if (xMessageDAOS.size() > 0) {
                            List<XMessageDAO> filteredList = new ArrayList<>();
                            for (XMessageDAO xMessageDAO : xMessageDAOS) {
                                if (xMessageDAO.getMessageState().equals(XMessage.MessageState.SENT.name()) ||
                                        xMessageDAO.getMessageState().equals(XMessage.MessageState.REPLIED.name()))
                                    filteredList.add(xMessageDAO);
                            }
                            if (filteredList.size() > 0) {
                                filteredList.sort(new Comparator<XMessageDAO>() {
                                    @Override
                                    public int compare(XMessageDAO o1, XMessageDAO o2) {
                                        return o1.getTimestamp().compareTo(o2.getTimestamp());
                                    }
                                });
                            }
                            return xMessageDAOS.get(0);
                        }
                        return new XMessageDAO();
                    }
                }).doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        log.error("Error in getLatestXMessage" + throwable.getMessage());
                    }
                });
    }

    /**
     * Switch from & To in XMessage
     *
     * @param xMessage
     */
    private void switchFromTo(XMessage xMessage) {
        SenderReceiverInfo from = xMessage.getFrom();
        SenderReceiverInfo to = xMessage.getTo();
        xMessage.setFrom(to);
        xMessage.setTo(from);
    }

    /**
     * Convert Federated users into chunks
     *
     * @param users
     * @param chunkSize
     * @return
     */
    private List<JSONArray> chunkArrayList(JSONArray users, int chunkSize) {
        if (users != null && users.length() > 0) {
            ArrayList<JSONArray> chunksList = new ArrayList<>();
            chunksList.add(new JSONArray());
            for (int x = 0; x < users.length(); x++) {
                JSONObject user = users.getJSONObject(x);
                if (chunksList.get(chunksList.size() - 1).length() == chunkSize)
                    chunksList.add(new JSONArray());
                chunksList.get(chunksList.size() - 1).put(user);
            }
            return chunksList;
        } else {
            log.error("Federated Users null found : " + users);
            return null;
        }
    }
}
