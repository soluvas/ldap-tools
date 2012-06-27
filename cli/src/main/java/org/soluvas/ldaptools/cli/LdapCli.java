package org.soluvas.ldaptools.cli;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import net.sourceforge.cardme.vcard.VCard;

import org.apache.directory.shared.ldap.model.entry.Entry;
import org.jboss.weld.environment.se.bindings.Parameters;
import org.jboss.weld.environment.se.events.ContainerInitialized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ActorSystem;
import akka.dispatch.Await;
import akka.dispatch.Future;
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import akka.util.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * @author ceefour
 */
public class LdapCli {
	private transient Logger log = LoggerFactory.getLogger(LdapCli.class);
	@Inject @Parameters String[] args;
	@Inject ActorSystem actorSystem;
	private ObjectMapper mapper;
	
	@Inject VCardReader vCardReader;
	@Inject VCard2EntryConverter vCard2EntryConverter;
	@Inject EntryAdder entryAdder;
	
	@PostConstruct public void init() {
		mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	}
	
	public void run(@Observes ContainerInitialized e) {
		log.info("ldap-cli starting");
		if (args.length < 1)
			throw new RuntimeException("Requires command line arguments.");
		
		try {
			if ("vcard-read".equals(args[0])) {
				// Parse vCard files 
				String[] fileNames = Arrays.copyOfRange(args, 1, args.length);
				List<File> files = Lists.transform(Arrays.asList(fileNames), new Function<String, File>() {
					@Override
					public File apply(String input) {
						return new File(input);
					}
				});
				List<VCard> vCards = Await.result(vCardReader.read(files), Duration.Inf());
				log.info("Parsed {} vCards", vCards.size());
				for (VCard card : vCards) {
					log.info("{}", card);
				}
			} else if ("vcard-to-ldif".equals(args[0])) {
				// Parse vCard files 
				String[] fileNames = Arrays.copyOfRange(args, 1, args.length);
				Future<Iterable<Entry>> entryIterFuture = Futures.traverse(Arrays.asList(fileNames), new akka.japi.Function<String, Future<Entry>>() {
					@Override
					public Future<Entry> apply(String input) {
						return vCardReader.read(new File(input)).flatMap(new Mapper<VCard, Future<Entry>>() {
							@Override
							public Future<Entry> apply(VCard vCard) {
								return vCard2EntryConverter.asEntry(vCard);
							}
						});
					}
				}, actorSystem.dispatcher());
				List<Entry> entries = ImmutableList.copyOf( Await.result(entryIterFuture, Duration.Inf()) );
				log.info("Got {} LDAP entries", entries.size());
				for (Entry entry : entries) {
					System.out.println(entry);
				}
			} else if ("import-vcard".equals(args[0])) {
				// Parse vCard files 
				String[] fileNames = Arrays.copyOfRange(args, 1, args.length);
				Future<Iterable<Entry>> entryIterFuture = Futures.traverse(Arrays.asList(fileNames), new akka.japi.Function<String, Future<Entry>>() {
					@Override
					public Future<Entry> apply(String input) {
						return vCardReader.read(new File(input))
								.flatMap(new Mapper<VCard, Future<Entry>>() {
							@Override
							public Future<Entry> apply(VCard vCard) {
								return vCard2EntryConverter.asEntry(vCard);
							}
						}).flatMap(new Mapper<Entry, Future<Entry>>() {
							@Override
							public Future<Entry> apply(Entry input) {
								Future<Entry> output = entryAdder.add(input);
								return output;
							}
						});
					}
				}, actorSystem.dispatcher());
				List<Entry> entries = ImmutableList.copyOf( Await.result(entryIterFuture, Duration.Inf()) );
				log.info("Added {} LDAP entries", entries.size());
			}
		} catch (Exception ex) {
			log.error("Error executing command", ex);
			throw new RuntimeException("Error executing command", ex);
		} finally {
			actorSystem.shutdown();
		}
	}
}
