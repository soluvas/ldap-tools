package org.soluvas.ldaptools.cli;

import java.io.FileReader;
import java.io.IOException;
import java.io.Serializable;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Properties;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.New;
import javax.enterprise.inject.Produces;
import javax.inject.Named;
import javax.net.ssl.X509TrustManager;

import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.directory.shared.ldap.model.exception.LdapException;
import org.apache.directory.shared.ldap.model.schema.SchemaManager;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.ContentEncodingHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.params.BasicHttpParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.soluvas.image.store.ImageStore;

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
	@Produces private LdapConnection ldap;
	@Produces private SchemaManager schemaManager;
	@Produces @Named("ldapUsersDn") private String ldapUsersDn;
	@Produces @Named("conversationPersonDomain") private String conversationPersonDomain;
	private Properties props;
	
	@PostConstruct public void init() throws IOException {
		log.info("Creating ActorSystem");
		actorSystem = ActorSystem.create("ldapcli");

		props = new Properties();
		props.load(new FileReader("ldap-cli.properties"));
		
		ldapUsersDn = props.getProperty("ldap.users.basedn");
		conversationPersonDomain = props.getProperty("conversation.person.domain");

		String bindHost = props.getProperty("ldap.bind.host");
		String bindPortStr = props.getProperty("ldap.bind.port");
		boolean bindSsl = Boolean.valueOf(props.getProperty("ldap.bind.ssl", "false"));
		String bindDn = props.getProperty("ldap.bind.dn");
		if (bindHost == null || bindPortStr == null || bindDn == null) {
			throw new RuntimeException("LDAP Configuration must be provided");
		} 
		int bindPort = Integer.valueOf(bindPortStr);
		
		log.info("Connecting to LDAP server {}:{} SSL={}", new Object[] { bindHost, bindPort, bindSsl });
		LdapConnectionConfig ldapConfig = new LdapConnectionConfig();
		ldapConfig.setLdapHost(bindHost);
		ldapConfig.setLdapPort(bindPort);
		ldapConfig.setUseSsl(bindSsl);
		X509TrustManager alwaysTrustManager = new X509TrustManager() {
			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return null;
			}
			
			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType)
					throws CertificateException {
				log.warn("Trusting {} SERVER certificate {}", authType, chain);
			}
			
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType)
					throws CertificateException {
				log.warn("Trusting {} CLIENT certificate {}", authType, chain);
			}
		};
		ldapConfig.setTrustManagers(alwaysTrustManager);
		ldap = new LdapNetworkConnection(ldapConfig);
		
		try {
			ldap.connect();
			ldap.bind(bindDn, props.getProperty("ldap.bind.password"));
		} catch (LdapException e) {
			throw new RuntimeException("Error during LDAP bind", e);
		}
		schemaManager = ldap.getSchemaManager();
		
		// this works:
		httpClient = new ContentEncodingHttpClient(new PoolingClientConnectionManager(), new BasicHttpParams());
		// this doesn't work:
//		 HttpClient httpClient = new DecompressingHttpClient(new DefaultHttpClient(new PoolingClientConnectionManager(), new BasicHttpParams()));
	}
	
	@PreDestroy public void destroy() {
		if (schemaManager != null) {
			schemaManager = null;
		}
		if (ldap != null) {
			try {
				ldap.close();
			} catch (IOException e) {
				log.warn("Error closing LDAP Connection", e);
			}
			ldap = null;
		}
		if (httpClient != null)
			httpClient.getConnectionManager().shutdown();
		
		log.info("Shutting down ActorSystem");
		actorSystem.shutdown();
		actorSystem.awaitTermination();
		actorSystem = null;
		log.info("ActorSystem shut down");
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
		imageStore.init(props.getProperty("image.dav.password"));
		return imageStore;
	}
	
	public void destroyPersonImageStore(@Disposes @Named("personImageStore") /*@PersonRelated*/ ImageStore imageStore) {
		imageStore.destroy();
	}
	
}
