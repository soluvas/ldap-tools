package org.soluvas.ldaptools.cli;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.inject.Named;

import net.sourceforge.cardme.vcard.VCard;
import net.sourceforge.cardme.vcard.features.PhotoFeature;

import org.apache.directory.shared.ldap.model.entry.Entry;
import org.jboss.weld.environment.se.bindings.Parameters;
import org.jboss.weld.environment.se.events.ContainerInitialized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.soluvas.image.store.ImageStore;
import org.soluvas.slug.SlugUtils;

import akka.actor.ActorSystem;
import akka.dispatch.Await;
import akka.dispatch.Future;
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import akka.util.Duration;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * @author ceefour
 */
public class LdapCli {
	private transient Logger log = LoggerFactory.getLogger(LdapCli.class);
	@Inject @Parameters String[] args;
	@Inject 
	ActorSystem actorSystem;
	
	@Inject 
	VCardReader vCardReader;
	@Inject 
	VCard2EntryConverter vCard2EntryConverter;
	@Inject 
	EntryAdder entryAdder;
	@Inject 
	@Named("personImageStore") ImageStore personImageStore;
	@Inject 
	PersonClear personClear;
	
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
			} else if ("import-vcardphoto".equals(args[0])) {
				// Parse vCard files 
				String[] fileNames = Arrays.copyOfRange(args, 1, args.length);
				Future<Iterable<Entry>> entryIterFuture = Futures.traverse(Arrays.asList(fileNames), new akka.japi.Function<String, Future<Entry>>() {
					@Override
					public Future<Entry> apply(String input) {
						return vCardReader.read(new File(input))
								.flatMap(new Mapper<VCard, Future<Entry>>() {
							@Override
							public Future<Entry> apply(final VCard vCard) {
								return vCard2EntryConverter.asEntry(vCard)
										.map(new Mapper<Entry, Entry>() {
										@Override
										public Entry apply(Entry entry) {
											try {
												if (vCard.hasPhotos()) {
													String name = vCard.getFormattedName().getFormattedName();
													log.debug("Reading photo for {} {} from vCard", name, entry.getDn());
													PhotoFeature photo = vCard.getPhotos().next();
													final byte[] photoBytes = photo.getPhoto();
													ByteArrayInputStream photoStream = new ByteArrayInputStream(photoBytes);
													log.info("Read {} bytes photo for {} {}", new Object[] { photoBytes.length, name, entry.getDn() });
													String personId = SlugUtils.generateId(name, 0);
													final String photoId = personImageStore.create(personId + ".jpg", photoStream, "image/jpeg", photoBytes.length,
															name);
													log.info("Created photo {} for {}", photoId, entry.getDn());
													entry.add("photoId", photoId);
												}
												return entry;
											} catch (Exception e) {
												throw new RuntimeException("Cannot add photoId to LDAP entry " + entry.getDn(), e);
											}
										}
									});
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
				log.info("Added {} LDAP entries with photos", entries.size());
			} else if ("person-clear".equals(args[0])) {
				// Delete all person
				Future<Iterable<String>> clearFuture = personClear.clear();
				Iterable<String> clearedIter = Await.result(clearFuture, Duration.Inf());
				ImmutableList<String> cleareds = ImmutableList.copyOf(clearedIter);
				log.info("Deleted {} entries: {}", cleareds.size(), cleareds);
			}
		} catch (Exception ex) {
			log.error("Error executing command", ex);
			throw new RuntimeException("Error executing command", ex);
		}
	}
	
	@PreDestroy public void destroy() throws InterruptedException {
		log.info("Bye byeeeeee");
		Executors.newSingleThreadScheduledExecutor().schedule(new Runnable() { 
			@Override
			public void run() {
				log.info("Exiting");
				System.exit(0);
			}
		}, 50, TimeUnit.MILLISECONDS);
	}
}
