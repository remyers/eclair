# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 28.1.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

### Offers

You can now create an offer with

```
./eclair-cli createoffer --description=coffee --amountMsat=20000 --expireInSeconds=3600 --issuer=me@example.com --blindedPathsFirstNodeId=03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f
```

All parameters are optional and omitting all of them will create a minimal offer with your public node id.
You can also disable offers and list offers with

```
./eclair-cli disableoffer --offer=lnoxxx
./eclair-cli listoffers
```

If you specify `--blindedPathsFirstNodeId`, your public node id will not be in the offer, you will instead be hidden behind a blinded path starting at the node that you have chosen.
You can configure the number and length of blinded paths used in `eclair.conf`:

```
offers {
  // Minimum length of an offer blinded path
  message-path-min-length = 2

  // Number of payment paths to put in Bolt12 invoices
  payment-path-count = 2
  // Length of payment paths to put in Bolt12 invoices
  payment-path-length = 4
  // Expiry delta of payment paths to put in Bolt12 invoices
  payment-path-expiry-delta = 500
}
```

### Simplified mutual close

This release includes support for the latest [mutual close protocol](https://github.com/lightning/bolts/pull/1205).
This protocol allows both channel participants to decide exactly how much fees they're willing to pay to close the channel.
Each participant obtains a channel closing transaction where they are paying the fees.

Once closing transactions are broadcast, they can be RBF-ed by calling the `close` RPC again with a higher feerate:

```sh
./eclair-cli close --channelId=<channel_id> --preferredFeerateSatByte=<rbf_feerate>
```

### Peer storage

With this release, eclair supports the `option_provide_storage` feature introduced in <https://github.com/lightning/bolts/pull/1110>.
When `option_provide_storage` is enabled, eclair will store a small encrypted backup for peers that request it.
This backup is limited to 65kB and node operators should customize the `eclair.peer-storage` configuration section to match their desired SLAs.
This is mostly intended for LSPs that serve mobile wallets to allow users to restore their channels when they switch phones.

### Eclair requires a  Java 21 runtime

Eclair now targets Java 21 and requires a compatible Java Runtime Environment. It will no longer work on JRE 11 or JRE 17.
There are many organisations that package Java runtimes and development kits, for example [OpenJDK 21](https://adoptium.net/temurin/releases/?package=jdk&version=21).

### API changes

<insert changes>

### Miscellaneous improvements and bug fixes

#### Gossip sync limits

On reconnection, eclair now only synchronizes its routing table with a small number of top peers instead of synchronizing with every peer.
If you already use `sync-whitelist`, the default behavior has been modified and you must set `router.sync.peer-limit = 0` to keep preventing any synchronization with other nodes.
You must also use `router.sync.whitelist` instead of `sync-whitelist`.

## Verifying signatures

You will need `gpg` and our release signing key 7A73FE77DE2C4027. Note that you can get it:

- from our website: https://acinq.co/pgp/drouinf.asc
- from github user @sstone, a committer on eclair: https://api.github.com/users/sstone/gpg_keys

To import our signing key:

```sh
$ gpg --import drouinf.asc
```

To verify the release file checksums and signatures:

```sh
$ gpg -d SHA256SUMS.asc > SHA256SUMS.stripped
$ sha256sum -c SHA256SUMS.stripped
```

## Building

Eclair builds are deterministic. To reproduce our builds, please use the following environment (*):

- Ubuntu 24.04.1
- Adoptium OpenJDK 21.0.4

Use the following command to generate the eclair-node package:

```sh
./mvnw clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.11.0... --format=oneline --reverse`>
