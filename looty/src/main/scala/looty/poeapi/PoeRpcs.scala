package looty
package poeapi

import looty.util.AjaxHelp
import AjaxHelp.HttpRequestTypes
import AjaxHelp.HttpRequestTypes.HttpRequestType

import scala.scalajs.js
import org.scalajs.jquery.JQueryStatic
import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}
import looty.views.Alerter


//////////////////////////////////////////////////////////////
// Copyright (c) 2013 Ben Jackman, Jeff Gomberg
// All Rights Reserved
// please contact ben@jackman.biz or jeff@cgtanalytics.com
// for licensing inquiries
// Created by bjackman @ 12/9/13 11:45 PM
//////////////////////////////////////////////////////////////


object PoeRpcs {

  import PoeTypes._

  def getAccountName() : Future[String] = {
    AjaxHelp[String]("http://www.pathofexile.com/my-account", HttpRequestTypes.Get, None).flatMap{ html =>
      val Regex = "href=\"/account/view-profile/([^\"]*)".r.unanchored
      html match {
        case Regex(accountName) => Future.successful(accountName)
        case _ => Future.failed(new Throwable("Unable to regex parse account name from page."))
      }
    }
  }

  def getCharacters(): Future[Characters] = {
    enqueue[js.Array[CharacterInfo]](url = "http://www.pathofexile.com/character-window/get-characters", params = null)
  }

  def getPassiveSkills(accountName: String, character: String): Future[PassivesTree] = {
    //Also reqData=0 is sent sometimes
    //TODO
    enqueue[PassivesTree](
      url = s"http://www.pathofexile.com/character-window/get-passive-skills?accountName=$accountName&character=$character",
      params = null,
      reqType = HttpRequestTypes.Get
    )
  }

  def getCharacterInventory(accountName : String, character: String): Future[Inventory] = {
    val p = newObject
    p.character = character
    p.accountName = accountName
    enqueue[Inventory](url = "http://www.pathofexile.com/character-window/get-items", params = p)
  }

  def getStashTab(league: String, tabIdx: Int): Future[StashTab] = {
    val p = newObject
    p.league = league.toString
    p.tabIndex = tabIdx

    enqueue[StashTab](url = "http://www.pathofexile.com/character-window/get-stash-items", params = p)
  }

  def getStashTabInfos(league: String): Future[StashTabInfos] = {
    val p = newObject
    p.league = league.toString
    p.tabIndex = 0
    p.tabs = 1

    enqueue[StashTab](url = "http://www.pathofexile.com/character-window/get-stash-items", params = p).map { stab =>
      stab.tabs.toOption.getOrElse(sys.error(s"Stash tab was not set in ${stab.toJsonString}"))
    }
  }


  //We send all rpc calls through a queue, since GGG throttles
  //the calls to their api we will need to re-attempt certain
  //calls on throttle failures
  def enqueue[A](url: String, params: js.Any, reqType: HttpRequestType = HttpRequestTypes.Post): Future[A] = {
    val qi = QueueItem(url, params, reqType)
    Q.addToQueue(qi)
    scheduleQueueCheck(wasThrottled = false)
    qi.getFuture.asInstanceOf[Future[A]]
  }

  def get[A](url: String, params: js.Any, reqType: HttpRequestType): Future[A] = {
    val jQuery = global.jQuery.asInstanceOf[JQueryStatic]
    AjaxHelp(url, reqType, params.nullSafe.map(s => jQuery.param(s))).flatMap { res: js.Any =>
      res.asInstanceOf[Any] match {
        case x: Boolean =>
          //GGG sends back "false" when the parameters aren't valid
          Future.failed(BadParameters(s"called $url with ${JSON.stringify(params)}"))
        case res => res.asInstanceOf[js.Dynamic].error.nullSafe match {
          case Some(reason) =>
            //Typically this is a throttle was tripped failure
            Future.failed(ThrottledFailure(reason.toString))
          //Therefore we schedule a re-attempt in the future
          case None => Future(res.asInstanceOf[A])
        }
      }
    }
  }

  def scheduleQueueCheck(wasThrottled: Boolean) {
    if (!willCheckQueue) {
      willCheckQueue = true
      global.setTimeout(() => checkQueue(), if (wasThrottled) coolOffMs else 0)
    }
  }

  def checkQueue() {
    willCheckQueue = false
    Q.peek() match {
      case Some(qi) =>
        qi.debugLog("Get")
        get[Any](qi.url, qi.params, qi.requestType).onComplete {
          case Success(x) =>
            qi.debugLog("Get => Success")
            Q.remove(qi)
            Alerter.info(s"Downloaded some data from pathofexile.com! If you like Looty please comment ${Alerter.featuresLink("here")} so more people find out about it! ")
            qi.success(x)
            checkQueue()
          case Failure(ThrottledFailure(msg)) =>
            qi.debugLog(s"Get => Throttled Failure $msg")
            console.debug("Throttled, cooling off ", qi.url, qi.params, msg)
            Alerter.warn(s"""Throttled by pathofexile.com, while you wait stop by ${Alerter.featuresLink("here")} and help other players discover the tool!""")
            scheduleQueueCheck(wasThrottled = true)
          case Failure(t) =>
            qi.debugLog(s"#### Get => Other Failure: $t")
            Q.remove(qi)
            Alerter.error("Unexpected connection error when talking to pathofexile.com, ensure that you are logged in.")
            qi.failure(t)
            checkQueue()
        }
      case None => //Do Nothing, queue is empty
        console.debug("Check Queue => None")
    }
  }


  private case class QueueItem(url: String, params: js.Any, requestType: AjaxHelp.HttpRequestTypes.HttpRequestType) {
    val id = Q.nextId
    private val promise = Promise[Any]()
    debugLog("Created Queue Item")
    def success(x : Any) = {
      debugLog("Success")
      if (!promise.isCompleted) {
        promise.success(x)
      } else {
        debugLog("#### DUPLICATE SUCCESS")
      }
    }
    def failure(t : Throwable) = {
      debugLog("Failure")
      if (!promise.isCompleted) {
        promise.failure(t)
      } else {
        debugLog("#### DUPLICATE FAILURE")
      }
    }
    def debugLog(msg : String) = {
      //console.debug(msg, id, promise.isCompleted, url, params)
    }
    def getFuture = promise.future
    def isCompleted = promise.isCompleted
  }

  private object Q {
    var id = 0
    def nextId = {
      id += 1
      id
    }
    def peek() : Option[QueueItem] = {
      val r = requestList.reverse.headOption
      r.map(_.debugLog("Peek"))
      r
    }

    def addToQueue(q : QueueItem) {
      q.debugLog("Add To Queue")
      requestList = q ::requestList
    }
    def remove(q: QueueItem) {
      requestList = requestList.filter(_.id != q.id)
    }

    private var requestList   = List.empty[QueueItem]
  }

  private var willCheckQueue = false
  //How long to wait after we hit the throttle before checking again
  val coolOffMs = 10000

  case class BadParameters(msg: String) extends Exception
  case class ThrottledFailure(msg: String) extends Exception


}