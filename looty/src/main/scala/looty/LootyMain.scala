package looty


import looty.poeapi.PoeRpcs
import looty.views.SettingsView

import scala.scalajs.js

import scala.concurrent.Future
import org.scalajs.jquery.JQueryStatic
import looty.poeapi.PoeTypes.Leagues
import looty.poeapi.{PoeCacherDemo, PoeCacher, PoeCacherChrome}
import looty.views.{MapsView, GlobalView, Alerter, PoeBuilderView, WealthView, XpView, LootView, HomeView, View}
import scala.util.Try
import scala.scalajs.js.annotation.JSExport
import looty.chrome.StoreMaster


//////////////////////////////////////////////////////////////
// Copyright (c) 2013 Ben Jackman, Jeff Gomberg
// All Rights Reserved
// please contact ben@jackman.biz or jeff@cgtanalytics.com
// for licensing inquiries
// Created by bjackman @ 12/9/13 11:22 PM
//////////////////////////////////////////////////////////////


class LootyApp(demoMode: Boolean) {

  implicit val pc: PoeCacher = {
    if (demoMode) new PoeCacherDemo() else new PoeCacherChrome()
  }

  var curView: View = null

  val jq: JQueryStatic = global.jQuery.asInstanceOf[JQueryStatic]

  def setView(v: View) {
    curView.nullSafe.foreach {_.stop()}
    curView = v
    val el = jq("#content")
    el.empty()
    curView.start(el)
  }


  def addRoutes(implicit pc: PoeCacher) {
    console.debug("Adding routes")
    val crossroads = global.crossroads
    val hasher = global.hasher
    val demoBanner = """!! THIS IS JUST A DEMO !! Please visit <a href="http://blog.jackman.biz/looty">here</a> to download the chrome extension, if you like what you see."""
    val banner = if (demoMode) demoBanner else ""
    val version = if (demoMode) "Demo" else Try(global.chrome.app.getDetails().version.asInstanceOf[String]).getOrElse("Unknown")
    crossroads.addRoute("home", () => setView(new HomeView(banner = banner, version = version)))
    for (league <- Leagues.all) {
      if (demoMode) {
        crossroads.addRoute(s"$league-grid", () => setView(new LootView(Leagues.Standard)))
      } else {
        val l = league.name.toLowerCase
        crossroads.addRoute(s"$l-grid", () => setView(new LootView(league)))
      }
    }
    crossroads.addRoute("xp", () => setView(new XpView))
    crossroads.addRoute("global", () => setView(new GlobalView))
    crossroads.addRoute("maps", () => setView(new MapsView))
    crossroads.addRoute("wealth", () => setView(new WealthView))
    crossroads.addRoute("poebuilder", () => setView(new PoeBuilderView))
    crossroads.addRoute("settings", () => setView(new SettingsView))
    crossroads.routed.add(global.console.log, console)
    if (hasher.getURL().toString.endsWith("home")) {
      hasher.setHash("home")
    }
    def parseHash(newHash: js.String, oldHash: js.UndefOr[String]) {
      crossroads.parse(newHash)
    }
    hasher.initialized.add(parseHash _)
    hasher.changed.add(parseHash _)
    hasher.init()
  }


  def initComponents(): Future[_] = {
    //ModsCsvParser.init()
    if (demoMode) {
      Future.successful(())
    } else {
      Future.sequence(List(StoreMaster.init()))
    }
  }

  def start() {
    console.log(s"Starting Looty in ${if (demoMode) "Demo" else "Extension"}")
    initComponents().foreach { _ =>
      addRoutes
    }
  }
}

@JSExport("LootyMain")
object LootyMain {
  @JSExport
  def main() {
    new LootyApp(demoMode = global.chrome.isUndefined || global.chrome.storage.isUndefined).start()
//    Alerter.info("Looking up accountName from GGGs website")
//    PoeRpcs.getAccountName().foreach {
//      case Some(accountName) =>
//        console.log(accountName)
//
//        Alerter.info(s"Looty Loaded! If you need help or want to help promote Looty please stop and leave a comment ${Alerter.featuresLink("here")}. Type 28 in the rLvl column to filter to pvp eligible items!")
//      case _ =>
//        console.log("NO MATCH FOUND")
//        new LootyApp(None, demoMode = global.chrome.isUndefined || global.chrome.storage.isUndefined).start()
//        Alerter.error("Unable to locate account name, You are probably not logged into path of exile account, please log in. If you are logged in specify your account name manually <a href=\"#/settings\">here</a>")
//      //Allow user to manually enter account name
//
//    }
  }

}

