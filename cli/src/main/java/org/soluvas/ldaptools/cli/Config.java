package org.soluvas.ldaptools.cli;

import java.io.IOException;
import java.util.Properties;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.directory.shared.ldap.model.exception.LdapException;
import org.apache.directory.shared.ldap.model.schema.SchemaManager;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.ContentEncodingHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.params.BasicHttpParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ActorSystem;

/**
 * @author ceefour
 *
 */
@ApplicationScoped
public class Config {

	private transient Logger log = LoggerFactory.getLogger(Config.class);
	@Produces private HttpClient httpClient;
	@Produces private LdapConnection ldap;
	@Produces private SchemaManager schemaManager;
	@Produces @Named("ldapUsersDn") private String ldapUsersDn;
	
	@PostConstruct public void init() throws IOException {
		Properties props = new Properties();
		props.load(getClass().getResourceAsStream("/ldap-cli.properties"));
		
		ldapUsersDn = props.getProperty("ldap.users.basedn");
		
		ldap = new LdapNetworkConnection(props.getProperty("ldap.bind.host"), Integer.valueOf(props.getProperty("ldap.bind.port")),
				Boolean.valueOf(props.getProperty("ldap.bind.ssl")));
		try {
			ldap.bind(props.getProperty("ldap.bind.dn"), props.getProperty("ldap.bind.password"));
		} catch (LdapException e) {
			throw new RuntimeException("Error during LDAP bind", e);
		}
		schemaManager = ldap.getSchemaManager();
		
		// this works:
		httpClient = new ContentEncodingHttpClient(new PoolingClientConnectionManager(), new BasicHttpParams());
		// this doesn't work:
//		 HttpClient httpClient = new DecompressingHttpClient(new DefaultHttpClient(new PoolingClientConnectionManager(), new BasicHttpParams()));
	}
	
	@Produces @Singleton ActorSystem createActorSystem() {
		return ActorSystem.create("ldap_cli");
	}
	
	public void destroyActorSystem(@Disposes @Singleton ActorSystem actorSystem) {
		actorSystem = null;
		actorSystem.shutdown();
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
	}
}
