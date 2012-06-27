package org.soluvas.ldaptools.cli;

import java.util.UUID;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.shared.ldap.model.entry.DefaultEntry;
import org.apache.directory.shared.ldap.model.entry.Entry;
import org.apache.directory.shared.ldap.model.exception.LdapEntryAlreadyExistsException;
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
				synchronized (ldap) {
					try {
						log.debug("Adding person LDAP Entry {}", entry.getDn().getName());
						ldap.add(entry);
		
						DefaultEntry addresses = new DefaultEntry(new Dn(new Rdn("ou=addresses"), entry.getDn()));
						addresses.add("objectClass", "organizationalUnit");
						log.debug("Adding addresses LDAP Entry {}", addresses.getDn());
						ldap.add(addresses);
						
						String homeAddressUid = UUID.randomUUID().toString();
						DefaultEntry homeAddress = new DefaultEntry(new Dn(new Rdn("uniqueIdentifier=" + homeAddressUid), addresses.getDn()));
						homeAddress.add("objectClass", "address", "extensibleObject");
						homeAddress.add("description", "Rumah");
						if (entry.containsAttribute("cn"))
							homeAddress.add(entry.get("cn"));
						if (entry.containsAttribute("o"))
							homeAddress.add(entry.get("o"));
						if (entry.containsAttribute("street"))
							homeAddress.add(entry.get("street"));
						if (entry.containsAttribute("l"))
							homeAddress.add(entry.get("l"));
						if (entry.containsAttribute("st"))
							homeAddress.add(entry.get("st"));
						if (entry.containsAttribute("c"))
							homeAddress.add(entry.get("c"));
						if (entry.containsAttribute("mobile"))
							homeAddress.add(entry.get("mobile"));
						if (entry.containsAttribute("telephoneNumber"))
							homeAddress.add(entry.get("telephoneNumber"));
						if (entry.containsAttribute("facsimileTelephoneNumber"))
							homeAddress.add(entry.get("facsimileTelephoneNumber"));
						if (entry.containsAttribute("mail"))
							homeAddress.add(entry.get("mail"));
						log.debug("Adding address LDAP Entry {}", homeAddress.getDn());
						ldap.add(homeAddress);
						
						DefaultEntry payments = new DefaultEntry(new Dn(new Rdn("ou=payments"), entry.getDn()));
						payments.add("objectClass", "organizationalUnit");
						log.debug("Adding addresses LDAP Entry {}", addresses.getDn());
						ldap.add(payments);
					
						return entry;
					} catch (LdapEntryAlreadyExistsException e) {
						// ignore if already exists, assume UID is correct
						log.error("Duplicate LDAP entry " + entry.getDn(), e);
						// FIXME: Utilize SlugUtils to generate alternative UID
						return entry;
					}
				}
			}
		}, actorSystem.dispatcher());
	}

}
