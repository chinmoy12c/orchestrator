package com.uci.orchestrator.Application;

import com.github.benmanes.caffeine.cache.Cache;
import com.uci.orchestrator.Drools.DroolsBeanFactory;
import com.uci.utils.BotService;
import com.uci.utils.kafka.ReactiveProducer;
import io.fusionauth.client.FusionAuthClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.kie.api.io.Resource;
import org.kie.api.runtime.KieSession;
import org.kie.internal.io.ResourceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class AppConfigOrchestrator {

    @Value("${spring.kafka.bootstrap-servers}")
    private String BOOTSTRAP_SERVERS;

    private final String GROUP_ID = "orchestrator";

    @Value("${campaign.url}")
    public String CAMPAIGN_URL;
    
    @Value("${campaign.admin.token}")
	public String CAMPAIGN_ADMIN_TOKEN;

    @Value("${fusionauth.url}")
    public String FUSIONAUTH_URL;

    @Value("${fusionauth.key}")
    public String FUSIONAUTH_KEY;

    @Value("${processOutbound}")
    private String processOutboundTopic;

    @Value("${inboundProcessed}")
    private String inboundProcessedTopic;

    @Value("${broadcast-transformer}")
    private String broadcastTransformerTopic;

    @Value("${generic-transformer}")
    private String genericTransformerTopic;

    @Autowired
    public Cache<Object, Object> cache;

    @Bean
    public FusionAuthClient getFAClient() {
        return new FusionAuthClient(FUSIONAUTH_KEY, FUSIONAUTH_URL);
    }

    @Bean
    public KieSession DroolSession() {
        Resource resource = ResourceFactory.newClassPathResource("OrchestratorRules.xlsx", getClass());
        KieSession kSession = new DroolsBeanFactory().getKieSession(resource);
        return kSession;
    }

    @Bean
    Map<String, Object> kafkaConsumerConfiguration() {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        configuration.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);
        configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);
        configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return configuration;
    }

    @Bean
    Map<String, Object> kafkaProducerConfiguration() {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        configuration.put(ProducerConfig.CLIENT_ID_CONFIG, "sample-producer");
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");
        configuration.put(org.springframework.kafka.support.serializer.JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);
        return configuration;
    }

    @Bean
    ReceiverOptions<String, String> kafkaReceiverOptions() {
        ReceiverOptions<String, String> options = ReceiverOptions.create(kafkaConsumerConfiguration());
        String[] inTopicName = new String[]{inboundProcessedTopic, processOutboundTopic};

        return options.subscription(Arrays.asList(inTopicName))
                .withKeyDeserializer(new JsonDeserializer<>())
                .withValueDeserializer(new JsonDeserializer(String.class));
    }

    @Bean
    SenderOptions<Integer, String> kafkaSenderOptions() {
        return SenderOptions.create(kafkaProducerConfiguration());
    }

    @Bean
    Flux<ReceiverRecord<String, String>> reactiveKafkaReceiver(ReceiverOptions<String, String> kafkaReceiverOptions) {
        return KafkaReceiver.create(kafkaReceiverOptions).receive();
    }

    @Bean
    KafkaSender<Integer, String> reactiveKafkaSender(SenderOptions<Integer, String> kafkaSenderOptions) {
        return KafkaSender.create(kafkaSenderOptions);
    }

    @Bean
    ReactiveProducer kafkaReactiveProducer() {
        return new ReactiveProducer();
    }
    
    @Bean
    ProducerFactory<String, String> producerFactory(){
    	ProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(kafkaProducerConfiguration());
    	return producerFactory;
    }
    
    @Bean
    KafkaTemplate<String, String> kafkaTemplate() {
    	KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(producerFactory());
    	return (KafkaTemplate<String, String>) kafkaTemplate;
    }

    /**
     * Create process outbound topic, if does not exists
     * @return
     */
    @Bean
    public NewTopic createProcessOutboundTopic() {
        return new NewTopic(processOutboundTopic, 1, (short) 1);
    }

    /**
     * Create broadcast transformer topic, if does not exists
     * @return
     */
    @Bean
    public NewTopic createBroadcastTransformerTopic() {
        return new NewTopic(broadcastTransformerTopic, 1, (short) 1);
    }

    /**
     * Create generic transformer topic, if does not exists
     * @return
     */
    @Bean
    public NewTopic createGenericTransformerTopic() {
        return new NewTopic(genericTransformerTopic, 1, (short) 1);
    }
}
