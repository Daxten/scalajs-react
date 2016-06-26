package japgolly.scalajs.react

import scala.{Either => Or}
import scalajs.js
import japgolly.scalajs.react.internal._
import Lifecycle._
import ScalaComponent._

object ScalaComponentB {

  type InitStateFnU[P, S]    = Component.Unmounted[P, Any]
  type InitStateArg[P, S]    = (InitStateFnU[P, S] => S) Or js.Function0[Box[S]]
  type NewBackendFn[P, S, B] = BackendScope[P, S] => B
  type RenderFn    [P, S, B] = MountedCB[P, S, B] => raw.ReactElement
  // Technically ↗ MountedCB ↗ should be Unmounted but because it can used to create event callbacks
  // (eg. onclick --> $.setState), it needs access to all Mounted methods.

  private val InitStateUnit : Nothing Or js.Function0[Box[Unit]] =
    Right(() => Box.Unit)

  // ===================================================================================================================

  final class Step1[P](name: String) {
    type Next[S] = Step2[P, S]

    // getInitialState is how it's named in React
    def getInitialState  [S](f: InitStateFnU[P, S] => S)            : Next[S] = new Step2(name, Left(f))
    def getInitialStateCB[S](f: InitStateFnU[P, S] => CallbackTo[S]): Next[S] = getInitialState(f.andThen(_.runNow()))

    // More convenient methods that don't need the full CompScope
    def initialState    [S](s: => S              ): Next[S] = new Step2(name, Right(() => Box(s)))
    def initialStateCB  [S](s: CallbackTo[S]     ): Next[S] = initialState(s.runNow())
    def initialState_P  [S](f: P => S            ): Next[S] = getInitialState[S]($ => f($.props))
    def initialStateCB_P[S](f: P => CallbackTo[S]): Next[S] = getInitialState[S]($ => f($.props).runNow())

    def stateless: Next[Unit] =
      new Step2(name, InitStateUnit)
  }

  // ===================================================================================================================

  final class Step2[P, S](name: String, initStateFn: InitStateArg[P, S]) {
    type Next[B] = Step3[P, S, B]

    def backend[B](f: NewBackendFn[P, S, B]): Next[B] =
      new Step3(name, initStateFn, f)

    def noBackend: Next[Unit] =
      backend(_ => ())

//    /**
//     * Shortcut for:
//     *
//     * {{{
//     *   .backend[B](new B(_))
//     *   .renderBackend
//     * }}}
//     */
//    def renderBackend[B]: PSBR[P, S, B] =
//      macro CompBuilderMacros.backendAndRender[P, S, B]
  }

  // ===================================================================================================================

  final class Step3[P, S, B](name: String, initStateFn: InitStateArg[P, S], backendFn: NewBackendFn[P, S, B]) {
    type Next[C <: ChildrenArg] = Step4[P, C, S, B]
    type $ = MountedCB[P, S, B]

    // TODO Hmmm, if no children are used, should not the .propsChildren methods be removed from {Unm,M}ounted?

    def render[C <: ChildrenArg](r: RenderFn[P, S, B]): Next[C] =
      new Step4[P, C, S, B](name, initStateFn, backendFn, r, Lifecycle.empty)

    // No children

     def renderPS(r: ($, P, S) => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($, $.props.runNow(), $.state.runNow()))

     def renderP(r: ($, P) => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($, $.props.runNow()))

     def renderS(r: ($, S) => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($, $.state.runNow()))

     def render_PS(r: (P, S) => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($.props.runNow(), $.state.runNow()))

     def render_P(r: P => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($.props.runNow()))

     def render_S(r: S => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($.state.runNow()))

    // Has children

     def renderPCS(r: ($, P, PropsChildren, S) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($, $.props.runNow(), $.propsChildren.runNow(), $.state.runNow()))

     def renderPC(r: ($, P, PropsChildren) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($, $.props.runNow(), $.propsChildren.runNow()))

     def renderCS(r: ($, PropsChildren, S) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($, $.propsChildren.runNow(), $.state.runNow()))

     def renderC(r: ($, PropsChildren) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($, $.propsChildren.runNow()))

     def render_PCS(r: (P, PropsChildren, S) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($.props.runNow(), $.propsChildren.runNow(), $.state.runNow()))

     def render_PC(r: (P, PropsChildren) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($.props.runNow(), $.propsChildren.runNow()))

     def render_CS(r: (PropsChildren, S) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($.propsChildren.runNow(), $.state.runNow()))

     def render_C(r: PropsChildren => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($.propsChildren.runNow()))

//    /**
//     * Use a method named `render` in the backend, automatically populating its arguments with props, state,
//     * propsChildren where needed.
//     */
//    def renderBackend: Out =
//      macro CompBuilderMacros.renderBackend[P, S, B]
  }

  // ===================================================================================================================

  final class Step4[P, C <: ChildrenArg, S, B](name       : String,
                                               initStateFn: InitStateArg[P, S],
                                               backendFn  : NewBackendFn[P, S, B],
                                               renderFn   : RenderFn[P, S, B],
                                               lifecycle  : Lifecycle[P, S, B]) {
    type This = Step4[P, C, S, B]

    private def copy(name       : String                = this.name       ,
                     initStateFn: InitStateArg[P, S]    = this.initStateFn,
                     backendFn  : NewBackendFn[P, S, B] = this.backendFn  ,
                     renderFn   : RenderFn    [P, S, B] = this.renderFn   ,
                     lifecycle  : Lifecycle   [P, S, B] = this.lifecycle  ): This =
      new Step4(name, initStateFn, backendFn, renderFn, lifecycle)

    private def lcAppend[I, O](lens: Lens[Lifecycle[P, S, B], Option[I => O]])(g: I => O)(implicit s: Semigroup[O]): This =
      copy(lifecycle = lifecycle.append(lens)(g)(s))

    /**
     * Invoked once, both on the client and server, immediately before the initial rendering occurs.
     * If you call `setState` within this method, `render()` will see the updated state and will be executed only once
     * despite the state change.
     */
    def componentWillMount(f: ComponentWillMountFn[P, S, B]): This =
      lcAppend(Lifecycle.componentWillMount)(f)

    /**
     * Invoked once, only on the client (not on the server), immediately after the initial rendering occurs. At this point
     * in the lifecycle, the component has a DOM representation which you can access via `ReactDOM.findDOMNode(this)`.
     * The `componentDidMount()` method of child components is invoked before that of parent components.
     *
     * If you want to integrate with other JavaScript frameworks, set timers using `setTimeout` or `setInterval`, or send
     * AJAX requests, perform those operations in this method.
     */
    def componentDidMount(f: ComponentDidMountFn[P, S, B]): This =
      lcAppend(Lifecycle.componentDidMount)(f)

    /**
     * Invoked before rendering when new props or state are being received. This method is not called for the initial
     * render or when `forceUpdate` is used.
     *
     * Use this as an opportunity to `return false` when you're certain that the transition to the new props and state
     * will not require a component update.
     *
     * If `shouldComponentUpdate` returns false, then `render()` will be completely skipped until the next state change.
     * In addition, `componentWillUpdate` and `componentDidUpdate` will not be called.
     *
     * By default, `shouldComponentUpdate` always returns `true` to prevent subtle bugs when `state` is mutated in place,
     * but if you are careful to always treat `state` as immutable and to read only from `props` and `state` in `render()`
     * then you can override `shouldComponentUpdate` with an implementation that compares the old props and state to their
     * replacements.
     *
     * If performance is a bottleneck, especially with dozens or hundreds of components, use `shouldComponentUpdate` to
     * speed up your app.
     */
    def shouldComponentUpdate(f: ShouldComponentUpdateFn[P, S, B]): This =
      lcAppend(Lifecycle.shouldComponentUpdate)(f)(Semigroup.either)

    def spec: raw.ReactComponentSpec = {
      val spec = js.Object().asInstanceOf[raw.ReactComponentSpec]

      @inline def vars($: raw.ReactComponent) = $.asInstanceOf[Vars[P, S, B]]

      for (n <- Option(name))
        spec.displayName = n

      def withMounted[A](f: MountedCB[P, S, B] => A): js.ThisFunction0[raw.ReactComponent, A] =
        (rc: raw.ReactComponent) =>
          f(rc.asInstanceOf[Vars[P, S, B]].mountedCB)

      spec.render = withMounted(renderFn)

      def getInitialStateFn: js.Function =
        initStateFn match {
          case Right(fn0) => fn0
          case Left(fn) => ((rc: raw.ReactComponentElement) => {
            val js = JsComponent.BasicUnmounted[Box[P], Box[S]](rc)
            Box(fn(js.mapProps(_.unbox)))
          }): js.ThisFunction0[raw.ReactComponentElement, Box[S]]
        }
      spec.getInitialState = getInitialStateFn

      val setup: raw.ReactComponent => Unit =
        rc => {
          val jMounted : JsMounted[P, S, B] = JsComponent.BasicMounted[Box[P], Box[S]](rc).addRawType[Vars[P, S, B]]
          val sMountedI: Mounted  [P, S, B] = new ScalaComponent.MountedF(jMounted)
          val sMountedC: MountedCB[P, S, B] = new ScalaComponent.MountedF(jMounted)
          val backend  : B                  = backendFn(sMountedC)
          jMounted.raw.mounted   = sMountedI
          jMounted.raw.mountedCB = sMountedC
          jMounted.raw.backend   = backend
        }
      spec.componentWillMount = lifecycle.componentWillMount match {
        case None    => setup
        case Some(f) =>
          ($: raw.ReactComponent) => {
            setup($)
            f(new ComponentWillMount(vars($))).runNow()
          }
      }

//        def onWillMountFn(f: DuringCallbackU[P, S, B] => Unit): Unit =
//          componentWillMountFn = Some(componentWillMountFn.fold(f)(g => $ => {g($); f($)}))
//        for (f <- lc.componentWillMount)
//          onWillMountFn(f(_).runNow())

//        for (f <- componentWillMountFn)
//          spec("componentWillMount") = f: ThisFunction
//
//
//        lc.getDefaultProps.flatMap(_.toJsCallback).foreach(spec("getDefaultProps") = _)
//
//        setThisFn1(                                             lc.componentWillUnmount     , "componentWillUnmount")
//        setThisFn1(                                             lc.componentDidMount        , "componentDidMount")
//        setFnPS   (ComponentWillUpdate      .apply[P, S, B, N])(lc.componentWillUpdate      , "componentWillUpdate")
//        setFnPS   (ComponentDidUpdate       .apply[P, S, B, N])(lc.componentDidUpdate       , "componentDidUpdate")
//        setFnP    (ComponentWillReceiveProps.apply[P, S, B, N])(lc.componentWillReceiveProps, "componentWillReceiveProps")

      lifecycle.componentDidMount.foreach(f =>
        spec.componentDidMount = ($: raw.ReactComponent) =>
          f(new ComponentDidMount(vars($))).runNow())

      lifecycle.shouldComponentUpdate.foreach(f =>
        spec.shouldComponentUpdate = ($: raw.ReactComponent, p: raw.Props, s: raw.State) =>
          f(new ShouldComponentUpdate(
            vars($).mounted,
            p.asInstanceOf[Box[P]].unbox,
            s.asInstanceOf[Box[S]].unbox)))

//        if (jsMixins.nonEmpty)
//          spec("mixins") = JArray(jsMixins: _*)
//
//        val spec2 = spec.asInstanceOf[ReactComponentSpec[P, S, B, N]]
//        lc.configureSpec.foreach(_(spec2).runNow())
//        spec2

      spec
    }

    def build(implicit ctorType: CtorType.Summoner[Box[P], C]): ScalaComponent[P, S, B, ctorType.CT] = {
      val rc = raw.React.createClass(spec)
      val jc = JsComponent[Box[P], C, Box[S]](rc)(ctorType).addRawType[Vars[P, S, B]](ctorType.pf)
      new ScalaComponent(jc)(ctorType.pf)
    }
  }
}
