package ibm.gse.eda.stores.infrastructure.rabbitmq;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import ibm.gse.eda.stores.domain.Item;
import ibm.gse.eda.stores.infrastructure.StoreRepository;
import io.smallrye.mutiny.Multi;

@ApplicationScoped
public class RabbitMQItemGenerator {

    private static Logger logger = Logger.getLogger(RabbitMQItemGenerator.class.getName());

    @ConfigProperty(name = "amqp.queue")
    public String queueName;
   
    @ConfigProperty(name = "amqp.host")
    public String hostname;
   
    @ConfigProperty(name = "amqp.port")
    public int port;

    @ConfigProperty(name = "amqp.username")
    public String username;
   
    @ConfigProperty(name = "amqp.password")
    public String password;
   
    @ConfigProperty(name = "amqp.virtualHost")
    public String virtualHost;
   
    @Inject
    public StoreRepository storeRepository;

    private Channel channel;
    private Connection connection;
    private ConnectionFactory factory;
    private Jsonb parser = JsonbBuilder.create();

    public RabbitMQItemGenerator(){}

    public List<Item> start(int records) {
        if (connectToQueueManager()) {
            List<Item> items = storeRepository.buildItems(records);
            Multi.createFrom().items(items.stream()).subscribe().with(item -> {
                sendMessage(item);
            }, failure -> System.out.println("Failed with " + failure.getMessage()));
            closeChannel();
            return items;
        }
        return new ArrayList<Item>();
    }

    public void sendMessage(Item item) {
        try {
            String messageToSend = parser.toJson(item);
            logger.info("send " + messageToSend);
            this.channel.basicPublish("", queueName, null, messageToSend.getBytes());
        } catch (IOException e) {
            logger.severe(e.getMessage());
        }
    }

    public boolean connectToQueueManager() {
        this.factory = new ConnectionFactory();
        this.factory.setHost(hostname);
        this.factory.setPort(port);
        this.factory.setUsername(username);
        this.factory.setPassword(password);
        this.factory.setVirtualHost(virtualHost);
        try {
            this.connection = factory.newConnection();
            this.channel = connection.createChannel();
            this.channel.queueDeclare(queueName, true, false, false, null);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private void closeChannel() {
        try {
            this.channel.close();
        } catch (IOException | TimeoutException e) {
            e.printStackTrace();
        }
    }

    public String getHost(){
        return this.hostname;
    }

    public int getPort(){
        return this.port;
    }

    public String toString(){
        return getHost() + ":" + getPort() + "\n\t" + queueName;
    }

	public Object getQueueName() {
		return this.queueName;
	}
}