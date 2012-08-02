package org.soluvas.ldaptools.cli;

import java.io.FileReader;
import java.io.IOException;
import java.io.Serializable;
import java.util.Properties;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.New;
import javax.enterprise.inject.Produces;
import javax.inject.Named;

import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapConnectionPool;
import org.apache.directory.ldap.client.api.PoolableLdapConnectionFactory;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DecompressingHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.params.BasicHttpParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.soluvas.image.store.ImageStore;
import org.soluvas.ldap.LdapUtils;

import akka.actor.ActorSystem;

/**
 * @author ceefour
 *
 */
@SuppressWarnings("serial")
@ApplicationScoped
public class Config implements Serializable {

	private transient Logger log = LoggerFactory.getLogger(Config.class);
	
	@Produces private ActorSystem actorSystem;
	@Produces private HttpClient httpClient;
	@Produces @Named("ldapUsersDn") private String ldapUsersDn;
	@Produces @Named("conversationPersonDomain") private String conversationPersonDomain;
	private Properties props;

	private LdapConnectionPool ldapPool;
	
	@PostConstruct public void init() throws IOException {
		log.info("Creating ActorSystem");
		actorSystem = ActorSystem.create("ldapcli");

		props = new Properties();
		props.load(new FileReader("ldap-cli.properties"));
		
		ldapUsersDn = props.getProperty("ldap.users.basedn");
		conversationPersonDomain = props.getProperty("conversation.person.domain");

		String ldapUri = props.getProperty("ldap.uri");
		String bindDn = props.getProperty("ldap.bind.dn");
		if (ldapUri == null || bindDn == null) {
			throw new RuntimeException("LDAP connection settings required");
		}
		log.debug("Connecting to LDAP server {} as {}", new Object[] { ldapUri, bindDn });
		String bindPassword = props.getProperty("ldap.bind.password");
		LdapConnectionConfig ldapConfig = LdapUtils.createTrustingConfig(ldapUri, bindDn, bindPassword);
		PoolableLdapConnectionFactory ldapConnectionFactory = new PoolableLdapConnectionFactory(ldapConfig);
		ldapPool = new LdapConnectionPool(ldapConnectionFactory);
		
		// DecompressingHttpClient works since httpclient 4.2.1
		 httpClient = new DecompressingHttpClient(new DefaultHttpClient(new PoolingClientConnectionManager(),
				 new BasicHttpParams()));
	}
	
	@PreDestroy public void destroy() {
		try {
			ldapPool.close();
		} catch (Exception e) {
			log.warn("Error closing LDAP connection pool", e);
		}
		if (httpClient != null)
			httpClient.getConnectionManager().shutdown();
		
		log.info("Shutting down ActorSystem");
		actorSystem.shutdown();
		actorSystem.awaitTermination();
		actorSystem = null;
		log.info("ActorSystem shut down");
	}
	
	@Produces public LdapConnection createLdapConnection() throws Exception {
		return ldapPool.getConnection();
	}
	
	public void destroyLdapConnection(@Disposes LdapConnection ldap) {
		try {
			ldapPool.releaseConnection(ldap);
		} catch (Exception e) {
			log.warn("Error releasing LDAP connection", e);
		}
	}
	@Produces @ApplicationScoped /*@PersonRelated*/ @Named("personImageStore") public ImageStore createPersonImageStore(@New ImageStore imageStore) {
		imageStore.setSystem(actorSystem);
		imageStore.addStyle("thumbnail", "t", 50, 50);
		imageStore.addStyle("small", "s", 128, 128);
		imageStore.addStyle("normal", "n", 240, 320);
		imageStore.setDavUri(props.getProperty("image.dav.uri"));
		imageStore.setPublicUri(props.getProperty("image.public.uri"));
		imageStore.setMongoUri(props.getProperty("image.mongo.uri"));
		imageStore.setNamespace("person");
		imageStore.init();
		return imageStore;
	}
	
	public void destroyPersonImageStore(@Disposes @Named("personImageStore") /*@PersonRelated*/ ImageStore imageStore) {
		imageStore.destroy();
	}
	
}
