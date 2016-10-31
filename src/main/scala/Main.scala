import java.util.concurrent.Executors
import javafx.application._
import javafx.event._
import javafx.geometry._
import javafx.scene._
import javafx.scene.canvas.Canvas
import javafx.scene.image._
import javafx.scene.input._
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.stage._

import rx.lang.scala._
import rx.lang.scala.schedulers._
import utils._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

object Main extends App{
    new Main().launch(args)
}

class Main extends Application {

  //val scheduler = NewThreadScheduler()
  val scheduler = TestScheduler()

  val resourceDir = s"file:///${System.getProperty("user.dir")}/src/main/resources"

  def launch(args: Array[String]) = Application.launch()

  def start(stage: Stage) {

    val screenWidth = 800
    val screenHeight = 600

    val root = new StackPane()
    //  ^
    //  |
    // -dy
    //  |
    // (0,0) -----dx--->
    root.setAlignment(Pos.BOTTOM_LEFT)
    implicit val scene = new Scene(root)

    // Time
    val clock = Observable
      .interval(initialDelay = 0 seconds, period = (1/60.0) second, scheduler)
      .map(_ => 1)
      .observeOn(PlatformScheduler())

    // Gravity constant
    val gravity = 0.1
    val jumpSpeed = 8

    // Background
    val sky = new Canvas(screenWidth,screenHeight) {
      root.getChildren.add(this)
      val context = getGraphicsContext2D
      context.setFill(Color.AZURE)
      context.fillRect(0,0, screenWidth,screenHeight)
    }

    // Grass
    val grassTile = new Image(s"$resourceDir/GrassBlock.png")
    val grassWidth = grassTile.getWidth
    val grassHeight = grassTile.getHeight
    val nrTiles = math.ceil(screenWidth/grassWidth).asInstanceOf[Int]+1

    // Place tiles on bottom, spaced grassWidth apart
    val grass = (0 to nrTiles-1).map(i => {
      val tile = new ImageView(grassTile)
      root.getChildren.add(tile)
      tile.setTranslateX(i*grassWidth)
      tile
    }).toList

    clock.map(_ => 1).subscribe(v => {

      grass.foreach(tile => {

        val dx = tile.getTranslateX
        if(dx <= -grassWidth) {
          tile.setTranslateX(screenWidth-v)
        } else {
          tile.setTranslateX(dx-v)
        }
      })
    })

    // Heart
    val heartTile = new Image(s"$resourceDir/Star.png")
    val starTile = new Image(s"$resourceDir/Heart.png")
    val heart = new ImageView(heartTile) {

      root.getChildren.add(this)
      setTranslateY(-(screenHeight-200))

      clock.map(_ => 3).subscribe(v => {
        if(getTranslateX <= -heartTile.getWidth) {
          setTranslateX(screenWidth-v)
        } else {
          setTranslateX(getTranslateX-v)
        }
      })
    }

    // Bug
    val bugTile = new Image(s"$resourceDir/EnemyBug.png")
    val bug = new ImageView(bugTile) {

      var jumps: Subject[Double] = Subject()

      // Poor man's physics
      //
      // ----------J--------------J---------------------------J-----------------
      //           |_j...0...-j0  |_j...0...-j0
      //

      val velocity: Observable[Double] = jumps.flatMap(v0 =>
        clock.scan(v0)((v,_)=>v-gravity)
             .map(v => if(v < -v0) 0 else v)
            .distinctUntilChanged
            .takeUntil(jumps)
      )
    }

    root.getChildren.add(bug)
    val bugHomeY = (-grassHeight/2)-5
    bug.setTranslateY(bugHomeY)
    bug.setTranslateX(screenHeight/2)

    bug.velocity.subscribe(dy => {
       bug.setTranslateY(bug.getTranslateY-dy)
    })

    spaceBar
      .filter(_ => bugHomeY-1 <= bug.getTranslateY)
      .doOnEach(_ => {
      new javafx.scene.media.AudioClip (s"$resourceDir/smb3_jump.wav").play()
    }).subscribe(_ => {
       bug.jumps.onNext(jumpSpeed)
    })

    enterKey.subscribe(_ => {
      scheduler match {
        case s: TestScheduler => s.advanceTimeBy((10 / 60.0) second)
      }
    })

    val heartPosition: Observable[Bounds] = clock.map(_ => heart.localToScene(heart.getLayoutBounds))
    val bugPosition: Observable[Bounds] = clock.map(_ => bug.localToScene(bug.getLayoutBounds))


    bugPosition.combineLatestWith(heartPosition)((bug: Bounds, heart: Bounds) => bug.intersects(heart))
      .slidingBuffer(2,1)
      .filter(hits => hits(0) != hits(1))
      .subscribe(hits => {
        if(!hits(0)) {
          heart.setImage(starTile)
          new javafx.scene.media.AudioClip(s"$resourceDir/smb3_coin.wav").play()
        }
        if(!hits(1)) {
          heart.setImage(heartTile)
        }
    })

    stage.setOnShown(new EventHandler[WindowEvent] {
      def handle(e: WindowEvent) = {
        new javafx.scene.media.AudioClip(s"$resourceDir/smb3_power-up.wav").play()
      }
    })

    stage.setTitle("Game")
    stage.setScene(scene)
    stage.show()
  }
}

package object utils {

  def keyPress (implicit scene: Scene) = Observable.create[KeyEvent](observer => {
    val handler = new EventHandler[input.KeyEvent] {
      def handle(e: input.KeyEvent): Unit  = observer.onNext(e)
    }
    scene.addEventHandler(KeyEvent.KEY_PRESSED, handler)
    Subscription { scene.removeEventHandler(KeyEvent.KEY_PRESSED, handler) }
  })

  def enterKey(implicit scene: Scene) = keyPress.filter(_.getCode == KeyCode.ENTER)
  def spaceBar(implicit scene: Scene) = keyPress.filter(_.getCode == KeyCode.SPACE)

}

object PlatformScheduler {

  val e = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor)
  val s: ExecutionContextScheduler = ExecutionContextScheduler(e)

  def apply(): Scheduler = s

}