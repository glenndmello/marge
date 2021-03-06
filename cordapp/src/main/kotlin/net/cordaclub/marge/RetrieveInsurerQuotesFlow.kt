package net.cordaclub.marge

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.seconds
import net.corda.core.utilities.unwrap
import java.util.*

object InsurerQuotingFlows {

    /**
     * First flow!
     * This is run by the Hospital when starting a treatment.
     *
     * 1) Creates the [TreatmentState] state that will evolve as the real treatment progresses.
     * 2) Requests quotes from Insurers.
     * 3) Selects the best quote and binds it in a transaction with the insurer.
     */
    @StartableByRPC
    @StartableByService
    @InitiatingFlow
    class RetrieveInsurerQuotesFlow(private val treatmentCoverageEstimation: TreatmentCoverageEstimation, private val insurers: List<Party>) : FlowLogic<SignedTransaction>() {

        companion object {
            private val log = loggerFor<RetrieveInsurerQuotesFlow>()
        }

        @Suspendable
        override fun call(): SignedTransaction {

            // Create and sign the Treatment State that will be used to justify the redemption of the Quote, and payment from the patient.
            val issueTreatmentTx = estimateTreatmentState()

            sleep(10.seconds)
            // Collect quotes from each insurer and select the best for the committed quote.
            val quotes = insurers.map { insurer ->
                // set up flow session with the insurer
                val session = initiateFlow(insurer)

                // send the claim request and receive the claim state
                val insurerQuote = session.sendAndReceive<Amount<Currency>>(treatmentCoverageEstimation).unwrap { it }

                println("Received quote: ${insurerQuote} from insurer ${insurer}")
                Pair(insurerQuote, session)
            }.sortedByDescending { it.first }

            for ((_, session) in quotes.drop(1)) {
                session.send(QuoteStatus.REJECTED_QUOTE)
            }
            val bestQuote = quotes.first()
            bestQuote.second.send(QuoteStatus.ACCEPTED_QUOTE)
            return createTransactionSignAndCommit(bestQuote.first, bestQuote.second, issueTreatmentTx)
        }

        @Suspendable
        private fun createTransactionSignAndCommit(amount: Amount<Currency>, session: FlowSession, treatmentTx: SignedTransaction): SignedTransaction {
            val insurer = session.counterparty
            val notary = serviceHub.networkMapCache.notaryIdentities.first()
            val treatment = treatmentTx.coreTransaction.outRef<TreatmentState>(0)
            val txb = TransactionBuilder(notary).apply {
                addCommand(Command(TreatmentCommand.QuoteTreatment(), listOf(insurer.owningKey, ourIdentity.owningKey)))
                addInputState(treatment)
                addOutputState(treatment.state.copy(data = treatment.state.data.let {
                    TreatmentState(
                            treatment = it.treatment,
                            estimatedTreatmentCost = treatmentCoverageEstimation.estimatedAmount,
                            treatmentCost = null,
                            amountPayed = null,
                            insurerQuote = InsurerQuote(insurer, amount),
                            treatmentStatus = TreatmentStatus.QUOTED,
                            linearId = it.linearId
                    )
                }))
            }
            val stx = serviceHub.signInitialTransaction(txb) // hospital signs the transaction
            val fullySignedTransaction = subFlow(CollectSignaturesFlow(stx, listOf(session)))
            return subFlow(FinalityFlow(fullySignedTransaction))
        }

        @Suspendable
        private fun estimateTreatmentState(): SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities.first()
            val txb = TransactionBuilder(notary).apply {
                addCommand(Command(TreatmentCommand.EstimateTreatment(), listOf(ourIdentity.owningKey)))
                addOutputState(
                        TreatmentState(
                                treatment = treatmentCoverageEstimation.treatment,
                                estimatedTreatmentCost = treatmentCoverageEstimation.estimatedAmount,
                                treatmentCost = null,
                                amountPayed = null,
                                insurerQuote = null,
                                treatmentStatus = TreatmentStatus.ESTIMATED),
                        TreatmentContract.CONTRACT_ID, notary)
            }
            val stx = serviceHub.signInitialTransaction(txb)

            return subFlow(FinalityFlow(stx))
        }
    }

    @CordaSerializable
    sealed class QuoteStatus {
        @CordaSerializable
        object REJECTED_QUOTE : QuoteStatus()

        @CordaSerializable
        object ACCEPTED_QUOTE : QuoteStatus()
    }

    /**
     * This class handles the insurers side of the flow and initiated by [RetrieveInsurerQuotesFlow]
     */
    @InitiatedBy(RetrieveInsurerQuotesFlow::class)
    class InsurerRespondFlow(private val session: FlowSession) : FlowLogic<SignedTransaction?>() {

        @Suspendable
        override fun call(): SignedTransaction? {
            val treatment = session.receive<TreatmentCoverageEstimation>().unwrap { treatmentCost ->
                requireThat {
                    //todo - check that the patient is insured by us
                }
                treatmentCost // return the claim because we've passed our checks for the payload
            }

            val quotedAmount = calculateAmountWeCanPay(treatment)
            val status = session.sendAndReceive<QuoteStatus>(quotedAmount).unwrap { it }

            if (status == QuoteStatus.ACCEPTED_QUOTE) {
                val signTransactionFlow = object : SignTransactionFlow(session, SignTransactionFlow.tracker()) {
                    override fun checkTransaction(stx: SignedTransaction) = requireThat {
                        val tx = stx.coreTransaction.outputsOfType<TreatmentState>().single()
                        stx.verify(serviceHub, checkSufficientSignatures = false)
                        "We sign for the treatment that we were required to quote." using (tx.treatment == treatment.treatment)
                        "We sign the amount we quoted" using (tx.insurerQuote == InsurerQuote(ourIdentity, quotedAmount))
                    }
                }
                // we invoke the sign Transaction flow which in turn awaits the CollectSignaturesFlow above
                return subFlow(signTransactionFlow)
            }
            return null
        }

        // This performs a highly complex algorithm.
        @Suspendable
        private fun calculateAmountWeCanPay(treatmentCoverageEstimation: TreatmentCoverageEstimation): Amount<Currency> {
            val percentage = (Random().nextDouble() * 100).toInt()
            val amount = treatmentCoverageEstimation.estimatedAmount
            return amount.copy(quantity = (amount.quantity * percentage) / 100)
        }
    }
}
