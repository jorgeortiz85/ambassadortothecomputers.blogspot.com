trait LogicState { L =>
  type T[S,A]

  def fail[S,A]: T[S,A]
  def unit[S,A](a: A): T[S,A]
  def or[S,A](t1: T[S,A], t2: => T[S,A]): T[S,A]
  def apply[S,A,B](t: T[S,A], f: A => B): T[S,B]
  def bind[S,A,B](t: T[S,A], f: A => T[S,B]): T[S,B]
  def filter[S,A](t: T[S,A], p: A => Boolean): T[S,A]
  def split[S,A](s: S, t: T[S,A]): Option[(S,A,T[S,A])]

  def get[S]: T[S,S]
  def set[S](s: S): T[S, Unit]

  def or[S,A](as: List[A]): T[S,A] =
    as.foldRight(fail[S,A])((a, t) => or(unit(a), t))

  def run[S,A](s0: S, t: T[S,A], n: Int): List[(S,A)] = {
    def runAcc(t: T[S,A], n: Int, acc: List[(S,A)]): List[(S,A)] =
      if (n <= 0) acc.reverse else
        split(s0, t) match {
          case None => Nil
          case Some((s, a, t)) => runAcc(t, n - 1, (s, a) :: acc)
        }
    runAcc(t, n, Nil)
  }

  case class Syntax[S,A](t: T[S,A]) {
    def map[B](f: A => B): T[S,B] = L.apply(t, f)
    def filter(p: A => Boolean): T[S,A] = L.filter(t, p)
    def flatMap[B](f: A => T[S,B]): T[S,B] = L.bind(t, f)
    def withFilter(p: A => Boolean): T[S,A] = L.filter(t, p)

    def |(t2: => T[S,A]): T[S,A] = L.or(t, t2)
    def &[B](t2: => T[S,B]): T[S,B] = L.bind(t, { _: A => t2 })
  }

  implicit def syntax[S,A](t: T[S,A]) = Syntax(t)
}

object LogicStateSFK extends LogicState {
  type FK[R] = () => R
  type SK[S,A,R] = (S, A, FK[R]) => R

  trait T[S,A] { def apply[R](s: S, sk: SK[S,A,R], fk: FK[R]): R }

  def fail[S,A] =
    new T[S,A] {
      def apply[R](s: S, sk: SK[S,A,R], fk: FK[R]) = fk()
    }

  def unit[S,A](a: A) =
    new T[S,A] {
      def apply[R](s: S, sk: SK[S,A,R], fk: FK[R]) = sk(s, a, fk)
    }

  def or[S,A](t1: T[S,A], t2: => T[S,A]) =
    new T[S,A] {
      def apply[R](s: S, sk: SK[S,A,R], fk: FK[R]) = t1(s, sk, { () => t2(s, sk, fk) })
    }

  def bind[S,A,B](t: T[S,A], f: A => T[S,B]) =
    new T[S,B] {
      def apply[R](s: S, sk: SK[S,B,R], fk: FK[R]) =
        t(s, ({ (s, a, fk) => f(a)(s, sk, fk) }: SK[S,A,R]), fk)
    }

  def apply[S,A,B](t: T[S,A], f: A => B) =
    new T[S,B] {
      def apply[R](s: S, sk: SK[S,B,R], fk: FK[R]) =
        t(s, ({ (s, a, fk) => sk(s, f(a), fk) }: SK[S,A,R]), fk)
    }

  def filter[S,A](t: T[S,A], p: A => Boolean) =
    new T[S,A] {
      def apply[R](s: S, sk: SK[S,A,R], fk: FK[R]) =
        t(s, ({ (s, a, fk) => if (p(a)) sk(s, a, fk) else fk() }: SK[S,A,R]), fk)
    }

  def split[S,A](s: S, t: T[S,A]) = {
    def stateUnit[S,A](s: S, a: A): T[S,A] =
      new T[S,A] {
        def apply[R](s: S, sk: SK[S,A,R], fk: FK[R]) = sk(s, a, fk)
      }
    def unsplit(r: () => Option[(S,A,T[S,A])]): T[S,A] =
      r() match {
        case None => fail
        case Some((s, a, t)) => or(stateUnit(s, a), t)
      }
    def sk : SK[S,A,Option[(S,A,T[S,A])]] =
      { (s, a, fk) => Some((s, a, unit(fk).flatMap(unsplit))) }
    t(s, sk, { () => None })
  }

  def get[S]: T[S,S] =
    new T[S,S] {
      def apply[R](s: S, sk: SK[S,S,R], fk: FK[R]) = sk(s, s, fk)
    }

  def set[S](s: S): T[S,Unit] =
    new T[S,Unit] {
      def apply[R](_s: S, sk: SK[S,Unit,R], fk: FK[R]) = sk(s, (), fk)
    }
}

object LogicStateSKE extends LogicState {
  type T[S,A] = (S, ((S, A) => Unit)) => Unit

  def fail[S,A] = { (s, sk) => throw Fail }

  def unit[S,A](a: A) = { (s, sk) => sk(s, a) }

  def or[S,A](t1: T[S,A], t2: => T[S,A]) =
    { (s, sk) =>
      try { t1(s, sk) }
      catch { case Fail => t2(s, sk) }
    }

  def bind[S,A,B](t: T[S,A], f: A => T[S,B]) =
    { (s, sk) => t(s, { (s, a) => f(a)(s, sk) }) }

  def apply[S,A,B](t: T[S,A], f: A => B) =
    { (s, sk) => t(s, { (s, a) => sk(s, f(a)) }) }

  def filter[S,A](t: T[S,A], p: A => Boolean) =
    { (s, sk) =>
      t(s, { (s, a) => if (p(a)) sk(s, a) else throw Fail })
    }

  def split[S,A](s: S, t: T[S,A]) = throw new Exception("unimplemented")

  override def run[S,A](s: S, t: T[S,A], n: Int): List[(S,A)] = {
    if (n <= 0) return Nil
    val lb = new scala.collection.mutable.ListBuffer[(S,A)]
    def sk(s: S, a: A) = {
      lb += ((s, a))
      throw (if (lb.size < n) Fail else Finish)
    }
    try {
      t(s, sk)
      throw new Exception("not reached")
    }
    catch { case Fail | Finish => lb.result }
  }

  def get[S] = { (s, sk) => sk(s, s) }

  def set[S](s: S) = { (_s, sk) => sk(s, ()) }
}
