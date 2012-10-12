package org.soluvas.ldaptools.cli;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.inject.Named;

import net.sourceforge.cardme.vcard.VCard;
import net.sourceforge.cardme.vcard.features.PhotoFeature;

import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.shared.ldap.model.entry.Attribute;
import org.apache.directory.shared.ldap.model.entry.Entry;
import org.apache.directory.shared.ldap.model.message.SearchScope;
import org.jboss.weld.environment.se.bindings.Parameters;
import org.jboss.weld.environment.se.events.ContainerInitialized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.soluvas.image.store.ImageRepository;
import org.soluvas.ldap.LdapUtils;

import akka.actor.ActorSystem;
import akka.dispatch.Await;
import akka.dispatch.Future;
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import akka.util.Duration;
import au.com.bytecode.opencsv.CSVWriter;

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
	@Named("personImageStore") ImageRepository personImageStore;
	@Inject 
	PersonClear personClear;
	@Inject @Named("ldapUsersDn") private String ldapUsersDn;
	@Inject private transient LdapConnection ldap;
	
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
												// Generate a new, "valid" UID
												// TODO: This is not always safe because in reality, several threads or even different processes
												// can insert entries at the same time
//												String personId = SlugUtils.generateValidId(entry.get("cn").getString(), new Predicate<String>() {
//													@Override
//													public boolean apply(String id) {
//														synchronized (ldap) {
//															try {
//																return !ldap.exists("uid=" + id + "," + ldapUsersDn);
//															} catch (LdapException e) {
//																throw new RuntimeException("Cannot check LDAP entry", e);
//															}
//														}
//													}
//												});
//												entry.setDn("uid=" + personId + "," + ldapUsersDn);
												String personId = entry.getDn().getRdn().getValue().getString();
												
												if (vCard.hasPhotos()) {
													String name = vCard.getFormattedName().getFormattedName();
													log.debug("Reading photo for {} {} from vCard", name, entry.getDn());
													PhotoFeature photo = vCard.getPhotos().next();
													final byte[] photoBytes = photo.getPhoto();
													ByteArrayInputStream photoStream = new ByteArrayInputStream(photoBytes);
													log.info("Read {} bytes photo for {} {}", new Object[] { photoBytes.length, name, entry.getDn() });
													try {
														final String photoId = personImageStore.create(personId + ".jpg", photoStream, "image/jpeg", photoBytes.length,
																name);
														log.info("Created photo {} for {}", photoId, entry.getDn());
														entry.add("photoId", photoId);
													} catch (Exception ex) {
														// cannot upload photo? perhaps broken file/format. log the error
														log.error("Cannot upload photo for LDAP entry " + entry.getDn(), ex);
														// and continue as if nothing happened
													}
												}
												return entry;
											} catch (Exception ex) {
												throw new RuntimeException("Cannot add photoId to LDAP entry " + entry.getDn(), ex);
											}
										}
									});
							}
						}).flatMap(new Mapper<Entry, Future<Entry>>() {
							@Override
							public Future<Entry> apply(Entry entry) {
								Future<Entry> output = entryAdder.add(entry);
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
			} else if ("personphoto-clear".equals(args[0])) {
				// Delete all person with photos
				Future<List<Entry>> peopleFuture = personClear.findAll();
				Future<Iterable<String>> clearFuture = peopleFuture.flatMap(new Mapper<List<Entry>, Future<Iterable<String>>>() {
					@Override
					public Future<Iterable<String>> apply(List<Entry> entries) {
						return Futures.traverse(entries, new akka.japi.Function<Entry, Future<String>>() {
							@Override
							public Future<String> apply(final Entry entry) {
								// delete photo first, then delete the entry
								return Futures.future(new Callable<Entry>() {
									@Override
									public Entry call() throws Exception {
										if (entry.containsAttribute("photoId")) {
											// attempt to delete the photo
											String photoId = entry.get("photoId").getString();
											try {
												personImageStore.delete(photoId);
											} catch (Exception e) {
												log.error("Cannot delete photo " + photoId + " for entry " + entry.getDn(), e);
											}
										}
										return entry;
									}
								}, actorSystem.dispatcher()).flatMap(new Mapper<Entry, Future<String>>() {
									@Override
									public Future<String> apply(Entry entry) {
										return personClear.deleteRecursively(entry);
									}
								});
							}
						}, actorSystem.dispatcher()); 
					}
				});
				Iterable<String> clearedIter = Await.result(clearFuture, Duration.Inf());
				List<String> cleareds = ImmutableList.copyOf(clearedIter);
				log.info("Deleted {} entries: {}", cleareds.size(), cleareds);
			} else if ("person-ls".equals(args[0])) {
				// List all person
				Future<List<Entry>> peopleFuture = personClear.findAll();
				List<Entry> people = Await.result(peopleFuture, Duration.Inf());
				log.info("Got {} entries: {}", people.size());
				for (Entry person : people) {
					System.out.println(person.getDn().getRdn().getValue().getString());
				}
			} else if ("export-csv".equals(args[0])) {
				// List all person
				final String exportFileName = "output/ldap_users.csv";
				log.info("Exporting LDAP entries to CSV: {}", exportFileName);
				CSVWriter csv = new CSVWriter(new FileWriter(exportFileName));
				try {
					List<Entry> entries = LdapUtils.asList( ldap.search(ldapUsersDn, "(objectclass=person)", SearchScope.ONELEVEL) );
					log.info("LDAP Search returned {} entries", entries.size());
					// get the attribute names first
					Set<String> attrNamesSet = new HashSet<String>();
					for (Entry entry : entries) {
						Collection<Attribute> attrs = entry.getAttributes();
						for (Attribute attr : attrs)
							attrNamesSet.add(attr.getId());
					}
					// exclude userPassword attribute
					attrNamesSet.remove("userpassword");
					log.info("Entries contain {} attributes: {}", attrNamesSet.size(), attrNamesSet);
					List<String> attrNames = ImmutableList.copyOf(attrNamesSet);
					csv.writeNext(attrNames.toArray(new String[] {}));
					// then fill entries
					for (final Entry entry : entries) {
						log.trace("Exporting entry {}", entry.getDn());
						List<String> row = Lists.transform(attrNames, new Function<String, String>() {
							@Override
							public String apply(String attrName) {
								if (entry.containsAttribute(attrName))
									return entry.get(attrName).get().getString();
								else
									return "";
							}
						});
						String[] rowValues = row.toArray(new String[] {});
						csv.writeNext(rowValues);
					}
					log.info("{} entries with {} attributes exported to {}", new Object[] {
							entries.size(), attrNamesSet.size(), exportFileName });
				} finally {
					csv.close();
				}
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
