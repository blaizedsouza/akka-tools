package no.nextgentel.oss.akkatools.example2.trustaccountcreation

import java.util.concurrent.TimeUnit

import akka.actor.Status.Failure
import akka.actor.{ActorSystem, Props, ActorPath}
import no.nextgentel.oss.akkatools.aggregate._
import no.nextgentel.oss.akkatools.example2.other.{DoCreateTrustAccount, DoPerformESigning, DoSendEmailToCustomer}

import scala.concurrent.duration.FiniteDuration

class TACAggregate
(
  dmSelf:ActorPath,
  eSigningSystem:ActorPath,
  emailSystem:ActorPath,
  trustAccountSystem:ActorPath
) extends GeneralAggregateDMViaEvent[TACEvent, TACState](dmSelf) {

  override def persistenceIdBase() = TACAggregate.persistenceIdBase

  // Override this one to set different timeout
  override def idleTimeout() = FiniteDuration(60, TimeUnit.SECONDS)

  override var state = TACState.empty() // This is the state of our initial state (empty)

  // transform command to event
  override def cmdToEvent = {
    case c:CreateNewTACCmd        =>
      ResultingEvent( RegisteredEvent(c.info) )
        .onSuccess{ sender() ! "ok" }
        .onError{   (e) => sender() ! Failure(new Exception(s"Failed: $e"))}

    case c:ESigningFailedCmd      => ResultingEvent( ESigningFailedEvent() )
    case c:ESigningCompletedCmd   => ResultingEvent( ESigningCompletedEvent() )
    case c:CompletedCmd           => ResultingEvent( CreatedEvent(c.trustAccountId) )
    case c:DeclinedCmd            => ResultingEvent( DeclinedEvent(c.cause) )
  }

  override def generateDMs = {
    case e:RegisteredEvent  =>
      // We must send message to eSigningSystem
      val msg = DoPerformESigning(dispatchId, e.info.customerNo)
      ResultingDMs( msg, eSigningSystem)

    case e:ESigningCompletedEvent =>
      // ESigning is completed, so we should init creation of the TrustAccount
      val info = state.info.get
      val msg = DoCreateTrustAccount(dispatchId, info.customerNo, info.trustAccountType)
      ResultingDMs(msg, trustAccountSystem)


    case e:DeclinedEvent =>
      // The TrustAccountCreation-process failed - must notify customer
      val msg = DoSendEmailToCustomer(state.info.get.customerNo, s"Sorry.. TAC-failed: ${e.cause}")
      ResultingDMs(msg, emailSystem)

    case e:CreatedEvent =>
      // The TrustAccountCreation-process was success - must notify customer
      val msg = DoSendEmailToCustomer(state.info.get.customerNo, s"Your TrustAccount '${e.trustAccountId}' has been created!")
      ResultingDMs(msg, emailSystem)

  }
}

object TACAggregate {

  val persistenceIdBase = "TAC-"

  def props(dmSelf:ActorPath,
            eSigningSystem:ActorPath,
            emailSystem:ActorPath,
            trustAccountSystem:ActorPath) = Props(new TACAggregate(dmSelf, eSigningSystem, emailSystem ,trustAccountSystem))
}


class TACStarter(system:ActorSystem) extends AggregateStarter("tac", system) with AggregateViewStarter {

  def config(eSigningSystem:ActorPath,
             emailSystem:ActorPath,
             trustAccountSystem:ActorPath):TACStarter = {
    setAggregatePropsCreator{
      dmSelf =>
        TACAggregate.props(dmSelf, eSigningSystem, emailSystem, trustAccountSystem)
    }
    this
  }

  override def createViewProps(aggregateId: String): Props =
    Props( new GeneralAggregateView[TACEvent, TACState](TACAggregate.persistenceIdBase, aggregateId, TACState.empty(), true))
}