
#!/usr/bin/env ruby
# Note: ActiveLdap can't read Extended LDIF format, use ldapsearch with -L
# sudo gem install activeldap
require 'active_ldap'

ldif_data = ARGF.read
parsed = ActiveLdap::Ldif.parse(ldif_data)
sorted = parsed.sort_by { |entry| entry.dn.count(',') }
sorted.each { |entry|
  puts entry
  puts
}

