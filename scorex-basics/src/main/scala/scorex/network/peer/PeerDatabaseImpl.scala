package scorex.network.peer

import java.net.InetSocketAddress

import org.h2.mvstore.{MVMap, MVStore}
import scorex.app.Application

import scala.collection.JavaConversions._

class PeerDatabaseImpl(application: Application, filename: Option[String]) extends PeerDatabase {


  val database = filename match {
    case Some(file) => new MVStore.Builder().fileName(file).compress().open()
    case None => new MVStore.Builder().open()
  }

  private val whitelistPersistence: MVMap[InetSocketAddress, PeerInfo] = database.openMap("whitelist")
  private val blacklist: MVMap[InetSocketAddress, Long] = database.openMap("blacklist")

  private lazy val ownNonce = application.settings.nodeNonce

  override def addOrUpdateKnownPeer(address: InetSocketAddress, peerInfo: PeerInfo): Unit = {
    val updatedPeerInfo = Option(whitelistPersistence.get(address)).map { case dbPeerInfo =>
      val nonceOpt = peerInfo.nonce.orElse(dbPeerInfo.nonce)
      val nodeNameOpt = peerInfo.nodeName.orElse(dbPeerInfo.nodeName)
      PeerInfo(peerInfo.lastSeen, nonceOpt, nodeNameOpt)
    }.getOrElse(peerInfo)
    whitelistPersistence.put(address, updatedPeerInfo)
    database.commit()
  }

  override def blacklistPeer(address: InetSocketAddress): Unit = this.synchronized {
    whitelistPersistence.remove(address)
    blacklist += address -> System.currentTimeMillis()
    database.commit()
  }

  override def isBlacklisted(address: InetSocketAddress): Boolean =
    blacklist.synchronized(blacklist.contains(address))

  override def knownPeers(excludeSelf: Boolean): Map[InetSocketAddress, PeerInfo] =
    (excludeSelf match {
      case true => knownPeers(false).filter(_._2.nonce.getOrElse(-1) != ownNonce)
      case false => whitelistPersistence.keys.flatMap(k => Option(whitelistPersistence.get(k)).map(v => k -> v))
    }).toMap

  override def blacklistedPeers(): Seq[InetSocketAddress] =
    blacklist.keys.toSeq
}