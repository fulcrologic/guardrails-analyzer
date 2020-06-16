# ucv-biz

## Setup: Key Exchange

### Create and publish a GPG Key

Create a new key if you don't have one:

```
$ gpg --full-generate-key
```

You should make sure your public key is published:

```
$ gpg --list-keys
...
pub   rsa2048 2014-07-30 [SCA] [expires: 2019-07-29]
      942ABCB2803B4696A67BF4DFA11A83437BCDA065
uid           [ultimate] Tony Kay <tony.kay@gmail.com>
sub   rsa2048 2014-07-30 [E] [expires: 2019-07-29]
...
```

You send a key to the keyserver with its ID:

```
gpg --keyserver hkp://pool.sks-keyservers.net --send-key 942ABCB2803B4696A67BF4DFA11A83437BCDA065
```

### Sign Everyone Else's Key

Pull each partner's key (you'll have to ask them for their key ID):

```
$ gpg --recv-keys KEYID
```

and then sign it (assuming you only have one key for their email address) and send
the signed version back to them:

```
$ gpg --sign-key --ask-cert-level them@something.com
$ gpg -a --export KEYID > signed-key.asc
```

and send them the signed key (e.g. as an email attachment).

### Published your signed key

When you receive a signed version of your key from a partner, you should
merge it and (re)publish it:

```
$ gpg --import signed-key.asc
$ gpg --keyserver hkp://pool.sks-keyservers.net --send-key KEYID
```

## Legal Agreements

Agreements are in docs. Each doc should have a signature for each
person involved. This should be done with:

```
gpg --detach-sig -a -o docname.initials.asc docname
```

where `initials` are the initials of the person signing and `docname` is the name
of the file being signed.

Signatures can be verified with:

```
gpg --verify docname.initials.asc docname
```

If a document is updated, then a new detached signature must be created
(files are all overwritten and git history tracks the changes over time).

Ideally a new addendum is created instead (and signed), so that the original
and history is more easily readable.

## Timesheet format:

Timesheets are in `./timesheets`, and are
EDN files containing a single vector of entries like this:

```
[{:date "2018-10-13" :description "Meetings" :timespans [[1500 1630] [1800 2100]]}
 ...]
```

Where timespans is a vector of military times that are continuous spans of time
worked.  Wrapping across midnight is supported (though working more than 24 hours
in one sitting must be broken into separate spans).

NOTE: The times are integer read by read-string. Do NOT use octal accidentally:
0730 is *not* what you want. You want 730 for 7:30am and 1930 for 7:30pm.

The code for calculating totals does scan your input for octal four-digit numbers,
and will refuse to run if it finds one, so be careful not to include a span of
digits in the comments that looks like an octal number.

## Signing Timesheets

You can do the entire process with:

```
scripts/sign-timesheets initials
```

which will show you the latest log of time totals so you can eyeball it
for accuracy before your tag, will then commit any unstaged changes and
generate a signed tag with your default key.

### Manually signing

First, add sums to the timesheet log using `scripts/log-totals` and look at them
to make sure they make sense (e.g. someone didn't magically work an
unimaginable number of hours in the span).

Timesheets are "approved" by adding a signed tag to the repository and pushing it:

```
git tag -s yyyy-mm-dd.initials
```

where the tag name is an ISO date as shown, and the signer's initials.  This tag
technically secures all content in the repository, but according to the agreements it is
only used to verify (and validate over time) the acceptance of timesheets.  Other documents must
each be individually signed when accepted. This prevents repository tag signatures
from being legally binding on anything *except* timesheet content.

You should carefully review all timesheet totals before adding a signed tag
to the repository.

## Getting Totals

You can calculate your timesheet total using:

```
clj -A:dev:hours timesheets/file.edn
```
