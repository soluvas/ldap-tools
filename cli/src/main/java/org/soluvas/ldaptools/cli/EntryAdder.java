package org.soluvas.ldaptools.cli;

import java.util.concurrent.Callable;

import javax.inject.Inject;

import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.shared.ldap.model.entry.DefaultEntry;
import org.apache.directory.shared.ldap.model.entry.Entry;
import org.apache.directory.shared.ldap.model.name.Dn;
import org.apache.directory.shared.ldap.model.name.Rdn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ActorSystem;
import akka.dispatch.Future;
import akka.dispatch.Futures;

/**
 * Adds LDAP {@link Entry} to LDAP Repository using {@link LdapConnection}.
 * @author ceefour
 */
public class EntryAdder {
	
	private transient Logger log = LoggerFactory.getLogger(EntryAdder.class);
	@Inject private ActorSystem actorSystem;
	@Inject private LdapConnection ldap;
	
	public EntryAdder() {
		super();
	}
	
	public EntryAdder(ActorSystem actorSystem, LdapConnection ldap) {
		super();
		this.actorSystem = actorSystem;
		this.ldap = ldap;
	}
	
	public Future<Entry> add(final Entry entry) {
		return Futures.future(new Callable<Entry>() {
			@Override
			public Entry call() throws Exception {
				log.debug("Adding person LDAP Entry {}", entry.getDn().getName());
				ldap.add(entry);

				DefaultEntry addresses = new DefaultEntry(new Dn(new Rdn("ou=addresses"), entry.getDn()));
				addresses.add("objectClass", "organizationalUnit");
				log.debug("Adding addresses LDAP Entry {}", addresses.getDn().getName());
				ldap.add(addresses);
				
				DefaultEntry payments = new DefaultEntry(new Dn(new Rdn("ou=payments"), entry.getDn()));
				payments.add("objectClass", "organizationalUnit");
				log.debug("Adding addresses LDAP Entry {}", addresses.getDn().getName());
				ldap.add(payments);
			
				return entry;
			}
		}, actorSystem.dispatcher());
	}

}
