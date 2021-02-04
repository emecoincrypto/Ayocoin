package com.wavesplatform.it.sync.smartcontract

import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.crypto
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.api.TransactionInfo
import com.wavesplatform.it.sync.{minFee, setScriptFee, transferAmount}
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.it.util._
import com.wavesplatform.lang.v1.estimator.v2.ScriptEstimatorV2
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import com.wavesplatform.transaction.transfer.TransferTransaction

class SetScriptTransactionSuite extends BaseTransactionSuite {
  private lazy val fourthAddress: KeyPair = miner.createKeyPair()
  private lazy val fifthAddress: KeyPair = miner.createKeyPair()

  private lazy val acc0 = firstKeyPair
  private lazy val acc1 = secondKeyPair
  private lazy val acc2 = thirdKeyPair
  private lazy val acc3 = fourthAddress
  private lazy val acc4 = fifthAddress

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    miner.transfer(acc0, acc4.toAddress.toString, 10.waves, waitForTx = true)
  }

  test("set acc0 as 2of2 multisig") {
    for (v <- setScrTxSupportedVersions) {
      val contract = if (v < 2) acc0 else acc4
      val scriptText =
        s"""
        match tx {
          case _: Transaction => {
            let A = base58'${acc1.publicKey}'
            let B = base58'${acc2.publicKey}'
            let AC = sigVerify(tx.bodyBytes,tx.proofs[0],A)
            let BC = sigVerify(tx.bodyBytes,tx.proofs[1],B)
            AC && BC
          }
          case _ => false
        }
      """.stripMargin

      val contractBD  = miner.balanceDetails(contract.toAddress.toString)
      val script      = ScriptCompiler(scriptText, isAssetScript = false, ScriptEstimatorV2).explicitGet()._1.bytes().base64
      val setScriptId = miner.setScript(contract, Some(script), setScriptFee, version = v).id

      nodes.waitForHeightAriseAndTxPresent(setScriptId)

      val acc0ScriptInfo = miner.addressScriptInfo(contract.toAddress.toString)

      acc0ScriptInfo.script.isEmpty shouldBe false
      acc0ScriptInfo.scriptText.isEmpty shouldBe false

      acc0ScriptInfo.script.get.startsWith("base64:") shouldBe true

      miner.transactionInfo[TransactionInfo](setScriptId).script.get.startsWith("base64:") shouldBe true
      miner.assertBalances(contract.toAddress.toString, contractBD.regular - setScriptFee, contractBD.effective - setScriptFee)
    }
  }

  test("can't send from contract using old pk") {
    for (v <- setScrTxSupportedVersions) {
      val contract   = if (v < 2) acc0 else acc4
      val contractBD = miner.balanceDetails(contract.toAddress.toString)
      val acc3bd     = miner.balanceDetails(acc3.toAddress.toString)
      assertApiErrorRaised(
        miner.transfer(
          contract,
          recipient = acc3.toAddress.toString,
          assetId = None,
          amount = transferAmount,
          fee = minFee + 0.00001.waves + 0.00002.waves
        )
      )
      miner.assertBalances(contract.toAddress.toString, contractBD.regular, contractBD.effective)
      miner.assertBalances(acc3.toAddress.toString, acc3bd.regular, acc3bd.effective)
    }
  }

  test("can send from acc0 using multisig of acc1 and acc2") {
    for (v <- setScrTxSupportedVersions) {
      val contract   = if (v < 2) acc0 else acc4
      val contractBD = miner.balanceDetails(contract.toAddress.toString)
      val acc3BD     = miner.balanceDetails(acc3.toAddress.toString)
      val unsigned =
        TransferTransaction(
          version = 2.toByte,
          sender = contract.publicKey,
          recipient = acc3.toAddress,
          assetId = Waves,
          amount = 1000,
          feeAssetId = Waves,
          fee = minFee + 0.004.waves,
          attachment = ByteStr.empty,
          timestamp = System.currentTimeMillis(),
          proofs = Proofs.empty,
          acc3.toAddress.chainId
        )
      val sig1         = crypto.sign(acc1.privateKey, unsigned.bodyBytes())
      val sig2         = crypto.sign(acc2.privateKey, unsigned.bodyBytes())
      val signed       = unsigned.copy(proofs = Proofs(sig1, sig2))
      val transferTxId = miner.signedBroadcast(signed.json()).id

      nodes.waitForHeightAriseAndTxPresent(transferTxId)

      miner.assertBalances(
        contract.toAddress.toString,
        contractBD.regular - 1000 - minFee - 0.004.waves,
        contractBD.effective - 1000 - minFee - 0.004.waves
      )
      miner.assertBalances(acc3.toAddress.toString, acc3BD.regular + 1000, acc3BD.effective + 1000)
    }
  }

  test("can clear script at contract") {
    for (v <- setScrTxSupportedVersions) {
      val contract   = if (v < 2) acc0 else acc4
      val contractBD = miner.balanceDetails(contract.toAddress.toString)
      val unsigned = SetScriptTransaction
        .create(
          version = v,
          sender = contract.publicKey,
          script = None,
          fee = setScriptFee + 0.004.waves,
          timestamp = System.currentTimeMillis(),
          proofs = Proofs.empty
        )
        .explicitGet()
      val sig1 = crypto.sign(acc1.privateKey, unsigned.bodyBytes())
      val sig2 = crypto.sign(acc2.privateKey, unsigned.bodyBytes())

      val signed = unsigned.copy(version = v, proofs = Proofs(Seq(sig1, sig2)))

      val removeScriptId = miner
        .signedBroadcast(signed.json())
        .id

      nodes.waitForHeightAriseAndTxPresent(removeScriptId)

      miner.transactionInfo[TransactionInfo](removeScriptId).script shouldBe None
      miner.addressScriptInfo(contract.toAddress.toString).script shouldBe None
      miner.addressScriptInfo(contract.toAddress.toString).scriptText shouldBe None
      miner.assertBalances(
        contract.toAddress.toString,
        contractBD.regular - setScriptFee - 0.004.waves,
        contractBD.effective - setScriptFee - 0.004.waves
      )
    }
  }

  test("can send using old pk of contract") {
    for (v <- setScrTxSupportedVersions) {
      val contract   = if (v < 2) acc0 else acc4
      val contractBD = miner.balanceDetails(contract.toAddress.toString)
      val acc3BD     = miner.balanceDetails(acc3.toAddress.toString)
      val transferTxId = miner
        .transfer(
          contract,
          recipient = acc3.toAddress.toString,
          assetId = None,
          amount = 1000,
          fee = minFee,
          version = 2
        )
        .id

      nodes.waitForHeightAriseAndTxPresent(transferTxId)
      miner.assertBalances(contract.toAddress.toString, contractBD.regular - 1000 - minFee, contractBD.effective - 1000 - minFee)
      miner.assertBalances(acc3.toAddress.toString, acc3BD.regular + 1000, acc3BD.effective + 1000)
    }
  }
}
