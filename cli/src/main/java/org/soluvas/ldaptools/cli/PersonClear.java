package org.soluvas.ldaptools.cli;

import java.util.List;
import java.util.concurrent.Callable;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.shared.ldap.model.cursor.EntryCursor;
import org.apache.directory.shared.ldap.model.entry.Entry;
import org.apache.directory.shared.ldap.model.message.SearchScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ActorSystem;
import akka.dispatch.Future;
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import akka.japi.Function;

import com.google.common.collect.ImmutableList;

/**
 * Deletes all {@link Entry}es under a DN in an LDAP Repository, recursively.
 * @author ceefour
 */
public class PersonClear {
	
	private transient Logger log = LoggerFactory.getLogger(PersonClear.class);
	@Inject private ActorSystem actorSystem;
	@Inject private LdapConnection ldap;
	@Inject @Named("ldapUsersDn") private String ldapUsersDn;
	
	public PersonClear() {
		super();
	}
	
	public PersonClear(ActorSystem actorSystem, LdapConnection ldap) {
		super();
		this.actorSystem = actorSystem;
		this.ldap = ldap;
	}
	
	public Future<String> deleteRecursively(final Entry entry) {
		return Futures.future(new Callable<List<Entry>>() {
			@Override
			public List<Entry> call() throws Exception {
				log.debug("Preparing to delete {}, getting sub-entries...", entry.getDn());
				synchronized (ldap) {
					EntryCursor cursor = ldap.search(entry.getDn(), "(objectClass=*)", SearchScope.ONELEVEL, new String[] { });
					return ImmutableList.copyOf(cursor);
				}
			}
		}, actorSystem.dispatcher()).flatMap(new Mapper<List<Entry>, Future<Iterable<Void>>>() {
			@Override
			public Future<Iterable<Void>> apply(List<Entry> entries) {
				return Futures.traverse(entries, new Function<Entry, Future<Void>>() {
					@Override
					public Future<Void> apply(final Entry subEntry) {
						return deleteRecursively(subEntry)
								.map(new Mapper<String, Void>() {
							@Override
							public Void apply(String arg0) {
								return null;
							}
						});
					}
				}, actorSystem.dispatcher());
			}
		}).flatMap(new Mapper<Iterable<Void>, Future<String>>() {
			@Override
			public Future<String> apply(Iterable<Void> arg0) {
				return Futures.future(new Callable<String>() {
					@Override
					public String call() throws Exception {
						log.info("Deleting {}", entry.getDn());
						try {
							synchronized (ldap) {
								ldap.delete(entry.getDn());
							}
						} catch(Exception e) {
							// log error
							log.error("Cannot delete LDAP entry " + entry.getDn(), e);
							// but continue as if nothing happened
						}
						return entry.getDn().getName();
					}
				}, actorSystem.dispatcher());
			}
		});
	}
	
	public Future<Iterable<String>> clear() {
		return Futures.future(new Callable<List<Entry>>() {
			@Override
			public List<Entry> call() throws Exception {
				synchronized (ldap) {
					EntryCursor cursor = ldap.search(ldapUsersDn, "(objectClass=person)", SearchScope.ONELEVEL, "uid", "cn");
					return ImmutableList.copyOf(cursor);
				}
			}
		}, actorSystem.dispatcher())
		.flatMap(new Mapper<List<Entry>, Future<Iterable<String>>>() {
			@Override
			public Future<Iterable<String>> apply(List<Entry> entries) {
				return Futures.traverse(entries, new Function<Entry, Future<String>>() {
					@Override
					public Future<String> apply(Entry entry) {
						return deleteRecursively(entry);
					}
				}, actorSystem.dispatcher());
			}
		});
	}

}
