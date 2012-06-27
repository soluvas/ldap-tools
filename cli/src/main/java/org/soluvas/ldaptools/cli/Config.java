package org.soluvas.ldaptools.cli;

import java.io.IOException;
import java.util.Properties;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.inject.Singleton;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.ContentEncodingHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.params.BasicHttpParams;

import akka.actor.ActorSystem;

/**
 * @author ceefour
 *
 */
@ApplicationScoped
public class Config {

	@Produces private HttpClient httpClient;
	
	@PostConstruct public void init() throws IOException {
		Properties props = new Properties();
		props.load(getClass().getResourceAsStream("/ldap-cli.properties"));
		
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
		if (httpClient != null)
			httpClient.getConnectionManager().shutdown();
	}
}
