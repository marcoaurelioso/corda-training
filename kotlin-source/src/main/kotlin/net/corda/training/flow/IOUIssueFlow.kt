package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(val state: IOUState) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // Placeholder code to avoid type error when running the tests. Remove before starting the flow task!
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val issueCmd = Command(IOUContract.Commands.Issue(), state.participants.map { it.owningKey })

        val builder = TransactionBuilder(notary = notary)
        builder.withItems(issueCmd, StateAndContract(state, IOUContract.IOU_CONTRACT_ID))

        builder.verify(serviceHub)

        val ptx = serviceHub.signInitialTransaction(builder)

        val sessions = (state.participants - ourIdentity).map { initiateFlow(it) }

        val stx = subFlow(CollectSignaturesFlow(ptx, sessions))

        val ftx = subFlow(FinalityFlow(stx, sessions))

        return ftx
    }
}

/**
 * This is the flow which signs IOU issuances.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUIssueFlow::class)
/*class IOUIssueFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {*/
class IOUIssueFlowResponder(val flowSession: FlowSession): FlowLogic<SignedTransaction>() {
    @Suspendable
    //override fun call() {
    override fun call() : SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }
        /* subFlow(signedTransactionFlow) */
        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
        /* return subFlow(ReceiveFinalityFlow(flowSession, txWeJustSignedId)) */
    }
}