package ibm.gse.eda.stores.infrastructure.mq;

import java.io.File;
import java.util.List;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.jms.Destination;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.JMSProducer;
import javax.jms.TextMessage;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;

import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import ibm.gse.eda.stores.domain.Item;
import ibm.gse.eda.stores.infrastructure.StoreRepository;
import io.smallrye.mutiny.Multi;

@ApplicationScoped
public class MQItemGenerator {
    private static Logger logger = Logger.getLogger(MQItemGenerator.class.getName());

    @Inject
    @ConfigProperty(name = "mq.host")
    public String mqHostname;

    @Inject
    @ConfigProperty(name = "mq.port")
    public int mqHostport;

    @Inject
    @ConfigProperty(name = "mq.qmgr", defaultValue = "QM1")
    public String mqQmgr;

    @Inject
    @ConfigProperty(name = "mq.channel", defaultValue = "DEV.APP.SVRCONN")
    public String mqChannel;

    @Inject
    @ConfigProperty(name = "mq.app_user", defaultValue = "app")
    public String mqAppUser;

    @Inject
    @ConfigProperty(name = "mq.app_password", defaultValue = "passw0rd")
    public String mqPassword;

    @Inject
    @ConfigProperty(name = "mq.queue_name", defaultValue = "DEV.QUEUE.1")
    public String mqQueueName;

    @Inject
    @ConfigProperty(name = "app.name", defaultValue = "TestApp")
    public String appName;

    @Inject
    @ConfigProperty(name = "mq.cipher_suite")
    public Optional<String> mqCipherSuite;

    @Inject
    @ConfigProperty(name = "mq.ccdt_url")
    public Optional<String> mqCcdtUrl;

    @Inject
    public StoreRepository storeRepository;

    private Jsonb parser = JsonbBuilder.create();

    protected JMSProducer producer = null;
    private JMSContext jmsContext = null;
    private Destination destination = null;
    private JmsConnectionFactory cf = null;
    protected Jsonb jsonb = null;

	public List<Item> start(int numberOfRecords) {
        List<Item> items = storeRepository.buildItems(numberOfRecords);
        try {
            jmsContext = buildJMSConnectionSession();
            producer = jmsContext.createProducer();

            Multi.createFrom().items(items.stream()).subscribe().with(item -> {
                sendToMQ(item);
            }, failure -> System.out.println("Failed with " + failure.getMessage()));
        } catch (Exception e) {
            if (e != null) {
              if (e instanceof JMSException) {
                  processJMSException((JMSException) e);
              } else {
                  logger.error(e.getMessage());
                  logger.error(e.getCause());
              }
            }
        } finally {
            jmsContext.close();
        }

        return items;
	}

  private static void processJMSException(JMSException jmsex) {
      logger.info(jmsex.getMessage());
      Throwable innerException = jmsex.getLinkedException();
      logger.info("Exception is: " + jmsex);
      if (innerException != null) {
          logger.info("Inner exception(s):");
      }
      while (innerException != null) {
          logger.error(innerException.getMessage());
          innerException = innerException.getCause();
      }
      return;
  }

    private void sendToMQ(Item item) {
        String msg = parser.toJson(item);
        logger.info("sent to MQ:" + msg);
        TextMessage message = jmsContext.createTextMessage(msg);
        producer.send(destination, message);
    }

    private String validateCcdtFile(){
      /*
       *  Modeled after github.com/ibm-messaging/mq-dev-patterns/blob/master/JMS/com/ibm/mq/samples/jms/SampleEnvSetter.java
       */
      String value = mqCcdtUrl.orElse("");
      String filePath = null;
      if (value != null && ! value.isEmpty()) {
        logger.info("Checking for existence of file " + value);
        File tmp = new File(value);
        if (! tmp.exists()) {
          logger.info(value + " does not exist...");
          filePath = null;
        } else {
          logger.info(value + " exists!");
          filePath = value;
        }
      }
      return filePath;
    }

    private JMSContext buildJMSConnectionSession() throws JMSException {
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        cf = ff.createConnectionFactory();
        cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, this.mqQmgr);
        cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, this.appName);

        cf.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        cf.setStringProperty(WMQConstants.USERID, this.mqAppUser);
        cf.setStringProperty(WMQConstants.PASSWORD, this.mqPassword);

        String ccdtFilePath = validateCcdtFile();
        if(ccdtFilePath==null){
          logger.info("No valid CCDT file detected. Using host, port, and channel properties instead.");
          cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, this.mqHostname);
          cf.setIntProperty(WMQConstants.WMQ_PORT, this.mqHostport);
          cf.setStringProperty(WMQConstants.WMQ_CHANNEL, this.mqChannel);
        } else {
          logger.info("Setting CCDTURL to 'file://"+ccdtFilePath+"'");
          cf.setStringProperty(WMQConstants.WMQ_CCDTURL, "file://"+ccdtFilePath);
        }

        if( this.mqCipherSuite!=null && !("".equalsIgnoreCase(this.mqCipherSuite.orElse(""))) ) {
          cf.setStringProperty(WMQConstants.WMQ_SSL_CIPHER_SUITE, this.mqCipherSuite.orElse(""));
        }

        // Create JMS objects
        jmsContext = cf.createContext();
        destination = jmsContext.createQueue("queue:///" + this.mqQueueName);
        return jmsContext;
    }
}
