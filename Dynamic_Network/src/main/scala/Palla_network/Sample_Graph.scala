package Palla_network

import java.io.{FileWriter, PrintWriter}

import breeze.linalg.DenseVector
import breeze.numerics.I
import breeze.stats.distributions._
import cern.jet.math.Bessel
import org.apache.spark.{SparkConf, SparkContext}
import util.control.Breaks._
import scala.io.Source
//import edu.uci.ics.jung.graph.UndirectedSparseGraph
//import edu.uci.ics.jung.io.PajekNetReader
import org.apache.commons.math3.special.Gamma.logGamma
import org.apache.log4j.{Level,Logger}
import scala.util.Random
import scala.util.Random.nextDouble
object Sample_Graph{

  def main(args:Array[String]): Unit = {
    val niter = 100000
    val data = Source.fromFile("/home/jan/Downloads/temporaledges.txt").getLines.toArray
    val T = data.map(v => v.split(" ")(3).toInt).max
    val maxtime = 7
    val all_states = data.filter(v=>v.split(" ")(3).toInt <(maxtime+1)).map(v => (v.split(" ")(0).toInt, v.split(" ")(1).toInt))
    val nodes = all_states.map(v => Set(v._1, v._2)).reduce(_.union(_)).toList
    val K = nodes.length

    val networks = data.filter(v=>v.split(" ")(3).toInt <(maxtime+1)).foldLeft(Array.fill[Map[(Int, Int), Int]](maxtime)(all_states.map(v => (v, 0)).toMap)) { (s, i) =>
      val tmp = i.split(" ").map(v => v.toInt)
      s(tmp(3) - 1) = s(tmp(3) - 1) ++ Map((tmp(0), tmp(1)) -> tmp(2))
      s
    }
    //val pnl = new PajekNetReader(UndirectedSparseGraph.getFactory())
    //val D1 = Map((0,1)->1,(0,2)->0,(0,3)->2,(1,0)->1,(1,2)->1,(1,3)->3,(2,0)->0,(2,1)->1,(2,3)->5,(3,0)->2,(3,1)->3,(3,2)->5)
    //val D2 = Map((0,1)->1,(0,2)->1,(0,3)->1,(1,0)->1,(1,2)->0,(1,3)->2,(2,0)->1,(2,1)->0,(2,3)->3,(3,0)->1,(3,1)->2,(3,2)->3)
    //val D3 = Map((0,1)->0,(0,2)->1,(0,3)->2,(1,0)->0,(1,2)->0,(1,3)->3,(2,0)->1,(2,1)->0,(2,3)->4,(3,0)->2,(3,1)->3,(3,2)->4)
    //val networks = Array(D1,D2,D3)
    //val T = networks.length
    //val K = networks(0).keys.map(_._1).max+1
    //.slice(0, maxtime)
    val prior_alpha = new Gamma(1000, 0.001)
    val prior_tau = new Gamma(0.1, 1/0.1)
    val prior_phi = new Gamma(0.1, 1/0.1)
    val delta_alpha = 3.0
    val delta_phi = 2.0
    val delta_tau = 2.0

   Logger.getLogger("org").setLevel(Level.OFF)
   Logger.getLogger("akka").setLevel(Level.OFF)
    val conf = new SparkConf().setAppName("Sample_Graph").setMaster("local[*]")//.set("spark.network.timeout","10000s")
    val sc = new SparkContext(conf)
    var n_t = networks.map(v=>sc.parallelize(v.toList,24))
    var sanity_check = networks
    var phi =1.0
    var phi_prop = phi
    var tau = 1.0
    var tau_prop = tau
    var rho = Double.PositiveInfinity
    var alpha = 0.1
    var alpha_p = alpha
    var alpha_prop = alpha
    val L = 5
    val eps = 1e-4
    val w_0 = nodes.map(v => (v, 10*nextDouble)).toMap
    val w_0_ast = nextDouble*10.0
    var iter = 1
    var (w_0T, c_0T) = (0 until n_t.length).foldLeft((Array(w_0), Array.empty[Map[Int, Int]])) { (s, i) => //Array.fill[Map[Int,Int]](T)(nodes.map(v=>(v,0)).toMap)
      //print(iter)
      iter = iter + 1
      //val c_t = s._1(i).map(v => (v._1, zpois(phi * nextDouble*5.0)))
      //val c_t = s._1(i).map(v => (v._1, Poisson(phi * v._2).sample(1)(0)))
      val c_t = nodes.map(v=>(v, 1)).toMap
      //val c_t = s._1(i).map(v=>(v._1,Poisson(phi*v._2).sample(1)(0)+1))
     //val w_t1 = c_t.map(v =>(v._1, if (v._2 != 0) {Gamma(v._2, 1/(tau + phi)).sample(1)(0)}else{0.0}))
      val w_t1 = nodes.map(v => (v, 1*nextDouble)).toMap
     //val w_t1 = c_t.map(v =>(v._1, Gamma(v._2+0.1, 1/(tau + phi)).sample(1)(0)))
      //val w_t1 = nodes.map(v => (v, 5.0*nextDouble*Binomial(1,0.3).sample(1)(0))).toMap
      (s._1 :+ w_t1, s._2 :+ c_t)
    }
    c_0T = c_0T :+ w_0T(n_t.length).map(v => (v._1, Poisson(phi * v._2).sample(1)(0)))
    var (w_0T_ast, c_0T_ast) = (0 until n_t.length).foldLeft((Array(w_0_ast), Array[Int]())) { (s, i) =>
      //val c_t_ast = Poisson(phi * s._1(i)).sample(1)(0)
      val c_t_ast = Poisson(phi * 10.0*nextDouble).sample(1)(0)
      //val w_t1_ast = Gamma(alpha, tau + phi).sample(1)(0)
      val w_t1_ast = 10.0*nextDouble
      (s._1 :+ w_t1_ast, s._2 :+ c_t_ast)
    }
    c_0T_ast = c_0T_ast :+ Poisson(phi * w_0T_ast(n_t.length)).sample(1)(0)
    var i = 0
    for (iter_n <- (0 until niter)) {
      //printf("\n" + (iter_n).toString + "runs \n")
      for (i <- 0 until n_t.length) {
        //printf(i.toString)
        breakable {
          w_0T(i + 1) = update_wt(n_t(i), w_0T(i + 1), w_0T_ast(i + 1), c_0T(i + 1), c_0T(i), tau, phi, L, eps, sc,alpha)
          if (i != (n_t.length - 1)) {
            //print("\n")
            //print(c_0T(i+1).filter(v=>v._2!=0).slice(0,20))
            val tmp = update_ct(n_t(i), n_t(i + 1), w_0T(i + 1), w_0T(i + 2), w_0T_ast(i + 1), w_0T_ast(i + 2), c_0T(i + 1), c_0T(i + 2), c_0T(i), tau, phi, L, eps, sc)
            c_0T(i+1) = tmp._1
            w_0T(i+2) = tmp._2.foldLeft(w_0T(i+2))((s,i)=> s+(i._1->i._2))
            //print("\n")
            //print(c_0T(i+1).filter(v=>v._2!=0).slice(0,20))
            //print("\n")
          }
          for (j <- 0 until n_t.length - 1) {
            if (j != (n_t.length - 2)) {
              //println(c_0T_ast(j+1))
              c_0T_ast(j + 1) = update_c_t_ast(c_0T_ast(j + 1), w_0T_ast(j + 1), w_0T_ast(j + 2), phi, tau, alpha)
              //println(c_0T_ast(j+1))
            }
            //println(w_0T_ast(j+1))
            w_0T_ast(j + 1) = update_w_t_ast(c_0T_ast(j + 1), c_0T_ast(j), w_0T_ast(j + 1), w_0T(j + 1), phi, tau, alpha)
            //println(w_0T_ast(j+1))
          }
          /*
          alpha_p = Uniform(alpha - delta_alpha, alpha + delta_alpha).sample(1)(0)
          if (alpha_p < 0) {
            alpha_prop = -alpha_p
          } else {
            alpha_prop = alpha_p
          }*/
          //val alpha_tmp = Uniform(alpha-delta_alpha,alpha+delta_alpha).sample(1)(0)
          alpha_prop = alpha*math.exp(delta_alpha*Gaussian(0,1).sample(1)(0))//if (alpha_tmp<0){math.abs(alpha_tmp)}else if(alpha_tmp>10){20-alpha_tmp}else{alpha_tmp}
          if(alpha_prop>1e5){alpha=alpha}
          else if ((1 until w_0T.length).map{v =>
            val tmp = c_0T(v - 1).values.sum + c_0T_ast(v - 1) + alpha_prop
            val csum = if(tmp<0){Int.MaxValue}else{tmp}
            Gamma(csum, 1/(tau + phi)).logPdf(w_0T(v).values.sum + w_0T_ast(v))-
            Gamma(csum, 1/(tau + phi)).logPdf(w_0T(v).values.sum + w_0T_ast(v))}.sum+math.log(alpha_prop)-math.log(alpha) > math.log(nextDouble)
          ) {
            alpha = alpha_prop
          }
          phi_prop = phi * math.exp(delta_phi * Gaussian(0, 1).sample(1)(0))
          tau_prop = tau * math.exp(delta_tau * Gaussian(0, 1).sample(1)(0))

          val logr = (1 until w_0T.length - 1).map { v =>
           (cond_wt1(w_0T(v + 1), w_0T(v), phi_prop, tau_prop) zip cond_wt1(w_0T(v + 1), w_0T(v), phi, tau)).map(v=>v._1-v._2).filter(v=>v.isNaN !=true).foldLeft(0.0){(s,i)=>s+i }+
              /*.map(v => if (v == Double.PositiveInfinity) {
            BigDecimal(Double.MaxValue)
          } else if (v == Double.NegativeInfinity) {
            BigDecimal(Double.MinValue)
          } else {
            BigDecimal(v)
          })*/
              cond_wt_ast(w_0T_ast(v + 1), w_0T_ast(v), phi_prop, tau_prop, alpha)-cond_wt_ast(w_0T_ast(v + 1), w_0T_ast(v), phi, tau, alpha)
          }.filter(v=>v.isNaN !=true).foldLeft(0.0){(s,i) =>s+i} +/* - (1 until w_0T.length - 1).map { v =>
            cond_wt1(w_0T(v + 1), w_0T(v), phi, tau).map(v => if (v == Double.PositiveInfinity) {
            BigDecimal(Double.MaxValue)
          } else if (v == Double.NegativeInfinity) {
            BigDecimal(Double.MinValue)
          } else {
            BigDecimal(v)
          }) .sum -
              cond_wt_ast(w_0T_ast(v + 1), w_0T_ast(v), phi, tau, alpha)
          }.sum*/ math.log(phi_prop) - math.log(phi) + math.log(tau_prop) - math.log(tau)+prior_tau.logPdf(tau_prop)-prior_tau.logPdf(tau)+prior_phi.logPdf(phi_prop)-prior_phi.logPdf(phi)
          //printf("hyper:" +logr.toString+" %n")

          //if ((1 until w_0T.length - 1).map(v=>cond_wt1(w_0T(v + 1), w_0T(v), phi_prop, tau_prop).count(_.isInfinity)).sum !=0 ){break}
          if (logr.toDouble > math.log(nextDouble)) {

              phi = phi_prop
              tau = tau_prop

          }
          val pw_hyper = new PrintWriter(new FileWriter("hyper.txt", true))
          pw_hyper.write(Array(phi, tau, alpha).mkString(" "))
          pw_hyper.write(String.format("%n"))
          pw_hyper.close()
          //print("\n")
          //print(n_t(i).collect.filter(v=>v._2!=0).toList.sortWith(_._2>_._2).slice(0,20))
          n_t(i) = update_n(networks(i), n_t(i), sc.parallelize(networks(0).keys.map(v => (v, 0)).toList,24),
            networks(0).keys.map(v => (v, 0)).toMap, rho, w_0T(i + 1), networks(0).keys.map(v => (v, 0)).toMap,sc,alpha,tau)
          //print("\n")
          //print(n_t(i).collect.filter(v=>v._2!=0).toList.sortWith(_._2>_._2).slice(0,20))
          //print("\n")
          val pw_wa = new PrintWriter(new FileWriter("ast.txt", true))
          (w_0T_ast zip c_0T_ast).foreach{v=>
              pw_wa.write(Array(v._1,v._2).mkString(" "))
              pw_wa.write(" ")
          }
          pw_wa.write(String.format("%n"))
          pw_wa.close()
        }
        }
        for (k <- (0 until w_0T.length - 1)) {
          val pw_sociability = new PrintWriter(new FileWriter("sociability" + k.toString + ".txt", true))
          w_0T(k + 1).foreach { v =>
            pw_sociability.write(v._1.toString + ":" + v._2.toString)
            pw_sociability.write(" ")
          }
          pw_sociability.write(String.format("%n"))
          pw_sociability.close()
        }
      for (k <- (0 until c_0T.length - 1)) {
        val pw_c = new PrintWriter(new FileWriter("counts" + k.toString + ".txt", true))
        c_0T(k + 1).foreach { v =>
          pw_c.write(v._1.toString + ":" + v._2.toString)
          pw_c.write(" ")
        }
        pw_c.write(String.format("%n"))
        pw_c.close()
      }
      /*
        for (k <- (0 until n_t.length)) {
          val pw_n = new PrintWriter(new FileWriter("num" + k.toString + ".txt", true))
          n_t(k).foreach { v =>
            pw_n.write(v._1.toString + ":" + v._2.toString)
            pw_n.write(" ")
          }
          pw_n.write(String.format("%n"))
          pw_n.close()
      }*/
    }
  }
//sc.parallelize(n_t(0).keys.toList,1000).map(v=>List(v._1->(n_t(0).filter(vv=>vv._1._1==v._1).values.sum+n_t(0).filter(vv=>vv._1._2 == v._                                                                                                                                                                                                                                                                                                                                               1).values.sum)).toMap).reduce(_++_)

  def update_wt(D_t:org.apache.spark.rdd.RDD[((Int, Int), Int)],w_t:Map[Int,Double],w_t_ast:Double,c_t:Map[Int,Int],
                c_t_1:Map[Int,Int],tau:Double,phi:Double,L:Int,eps:Double,sc:SparkContext,alpha:Double): Map[Int,Double] ={
    val numpart = 24
    val D_tt = D_t //sc.parallelize(D_t.toList,8)
    val tmp_mt = sc.parallelize(w_t.toList,24).cogroup(D_tt.map(v=>(v._1._1,v._2)),D_tt.map(v=>(v._1._2,v._2)))
    val m_t = tmp_mt.map(v=>List(v._1->(v._2._2.toList.sum+v._2._3.toList.sum)).toMap).treeReduce(_++_,3)
   // val m_t = sc.parallelize(w_t.keys.toList,30).map(v=>List(v->(D_t.filter(vv=>vv._1._1==v).values.sum+D_t.filter(vv=>vv._1._2 == v).values.sum)).toMap).reduce(_++_)
    //val m_t = D_t.keys.map(v=>(v._1,D_t.filter(vv=>vv._1._1==v._1).values.sum+D_t.filter(vv=>vv._1._2 == v._1).values.sum)).toMap
    val w_tp = sc.parallelize(w_t.toList,numpart)
    val K = w_t.values.toArray.length
    val p = sc.parallelize(w_t.keys.toList,numpart).map(v=>(v,Gaussian(0,1).sample(1)(0)))
    val grad_0 = sc.parallelize(w_t.keys.toList,numpart).map(v=>(v,grad_U(v,m_t(v),c_t(v),c_t_1(v),tau,phi,w_t,w_t_ast,alpha)))
    val tmp = p.cogroup(grad_0)
    //print(w_t.keys.toList.map(v=>(v,grad_U(v,m_t(v),c_t(v),c_t_1(v),tau,phi,w_t,w_t_ast))))
    val pprop = tmp.map(v=>(v._1,v._2._1.toList(0)+eps/2*v._2._2.toList(0)))
    //pprop.take(10).foreach(println)
    //w_tp.take(10).foreach(println)
    val ret_prep = (0 until L).foldLeft(pprop.cogroup(w_tp)){(s,i)=>
      if (i==L-1){
        val wprop_last = s.map(v=>(v._1,if (math.exp(math.log(v._2._2.toList(0))+eps*v._2._1.toList(0)).isNaN || math.exp(math.log(v._2._2.toList(0))+eps*v._2._1.toList(0)).isInfinite){v._2._2.toList(0)}else{math.exp(math.log(v._2._2.toList(0))+eps*v._2._1.toList(0))}))
        //wprop_last.take(5).foreach(print)
        //print("\n")
        val tmp2 = wprop_last.map(vv=>List(vv._1->vv._2).toMap).reduce(_++_)
        val grad_last = wprop_last.map(v=> (v._1,grad_U(v._1,m_t(v._1),c_t(v._1),c_t_1(v._1),tau,phi,tmp2,w_t_ast,alpha)))
        val pprop_last = s.map(v=>(v._1,v._2._1.toList(0))).cogroup(grad_last).map(v=> (v._1,-(v._2._1.toList(0)+v._2._2.toList(0)*eps/2)))
        pprop_last.cogroup(wprop_last)
      }else{
        val wprop_l = s.map(v=>(v._1,if (math.exp(math.log(v._2._2.toList(0))+eps*v._2._1.toList(0)).isNaN || math.exp(math.log(v._2._2.toList(0))+eps*v._2._1.toList(0)).isInfinite){v._2._2.toList(0)}else{math.exp(math.log(v._2._2.toList(0))+eps*v._2._1.toList(0))}))
        //s.map(v=>(eps*v._2._1.toList(0),v._2._2.toList(0),math.exp(eps*v._2._1.toList(0)+math.log(v._2._2.toList(0))))).sortBy(v=> -v._2).take(10).foreach(print)
       // print("\n")
        //wprop_l.sortBy(v=> -v._2).take(1000).foreach(print)
        //print("\n")
        val tmp2= wprop_l.map(vv=>List(vv._1->vv._2).toMap).reduce(_++_)
        val grad_l = wprop_l.map(v=> (v._1,grad_U(v._1,m_t(v._1),c_t(v._1),c_t_1(v._1),tau,phi,tmp2,w_t_ast,alpha)))
        val pprop_l = s.map(v=>(v._1,v._2._1.toList(0))).cogroup(grad_l).map(v=> (v._1,v._2._1.toList(0)+v._2._2.toList(0)*eps))
        //pprop_l.sortBy(v=> - v._2).take(1000).foreach(print)
        //print("\n")
        //wprop_l.take(10).foreach(println)
        //print(wprop_l.map(_._2).reduce(_+_))
        //print(pprop_l.map(v=>v._2).reduce(_+_))
        pprop_l.cogroup(wprop_l)
      }
    }
    //print("\n")
    //print(ret_prep.map(v=>v._2._2.toList(0) == 0.0).collect.toList.count(_==true))
    //print("\n")
    //print(w_tp.map(v=>v._2==0.0).collect.toList.count(_ == true))
    //print("\n")
    //ret_prep.map(v=>v._2._2.toList(0)).take(5).foreach(println)

    val pprop_next = ret_prep.map(v=>(v._1,v._2._1.toList(0)))
    val logr = ret_prep.map(v=> (m_t(v._1)+c_t(v._1)+c_t_1(v._1))*(math.log(v._2._2.toList(0))-math.log(w_t(v._1)))).filter(v=>v.isNaN !=true).fold(0){(l,r)=>
      val tmp = if(r.isNegInfinity){Double.MinValue*1e10}else if(r.isPosInfinity){Double.MaxValue*1e-10}else{r}
      l+tmp}-
      ((ret_prep.cogroup(w_tp).map(v=>v._2._1.toList(0)._2.toList(0)+v._2._2.toList(0)).filter(v=>v.isNaN !=true).fold(0)(_+_)+2*w_t_ast)*
        (ret_prep.cogroup(w_tp).map(v=>v._2._2.toList(0)-v._2._1.toList(0)._2.toList(0)).filter(v=>v.isNaN !=true).fold(0)(_+_)))-
    //math.pow(ret_prep.map(v=>v._2._2.toList(0)).reduce((l,r) =>safeadd(l,r))+w_t_ast,2)
    //+math.pow(w_tp.map(v=>v._2).reduce((l,r) =>safeadd(l,r))+w_t_ast,2)-
     (2*phi+tau)*(ret_prep.map(v=>v._2._2.toList(0)).filter(v=>v.isNaN !=true).fold(0)(_+_)+w_tp.map(v=>v._2).filter(v=>v.isNaN !=true).fold(0)(_+_))-
      pprop_next.cogroup(p).map(v=>(v._2._1.toList(0)-v._2._2.toList(0))*(v._2._1.toList(0)+v._2._2.toList(0))).filter(v=>v.isNaN !=true).fold(0)(_+_)/2
    //val hoge = ret_prep.map(v=> (m_t(v._1)+c_t(v._1)+c_t_1(v._1))*(math.log(v._2._2.toList(0))-math.log(w_t(v._1)))).filter(v=>v.isNaN !=true)
    //print(ret_prep.map(v=> (m_t(v._1)+c_t(v._1)+c_t_1(v._1))*(math.log(v._2._2.toList(0))-math.log(w_t(v._1)))).filter(v=>v.isNaN !=true).fold(0)(_+_))
    //printf(" ")
    //print((ret_prep.cogroup(w_tp).map(v=>v._2._1.toList(0)._2.toList(0)+v._2._2.toList(0)).fold(0)(_+_)+2*w_t_ast))
    //printf("  ")
    //print(ret_prep.map(v=> (m_t(v._1)+c_t(v._1)+c_t_1(v._1))*(math.log(v._2._2.toList(0))-math.log(w_t(v._1)))).filter(v=>v.isNaN !=true).fold(0){(l,r)=>
      //val tmp = if(r.isNegInfinity){Double.MinValue*1e10}else if(r.isPosInfinity){Double.MaxValue*1e-10}else{r}
      //l+tmp})
    //printf(" ")
    //print((ret_prep.cogroup(w_tp).map(v=>v._2._2.toList(0)-v._2._1.toList(0)._2.toList(0)).fold(0)(_+_)))
    //printf(" w_ts:"+logr.toString+" \n")
    val pw_w = new PrintWriter(new FileWriter("w_accept.txt", true))
    pw_w.write(logr.toString)
    pw_w.write(String.format("%n"))
    pw_w.close()
    //print(logr)
    //val grad_0 = sc.parallelize(w_t.keys.toList,30).map(v=>List(v->grad_U(v,m_t(v),c_t(v),c_t_1(v),tau,phi,w_t,w_t_ast)).toMap)
    /*
    val (pprop_0,wprop_0) = (sc.parallelize(w_t.keys.toList,30).map(v=>List(v->(p(v)+eps/2*grad_0(v))).toMap).reduce(_++_),w_t)
    sc.parallelize(pprop_0.keys.toList.map(v=>List(v->(pprop_0(v),wprop_0(v))).toMap),30).map(v=>v.)

    val (pprop,wprop) = (1 until L).foldLeft(sc.parallelize(pprop_0.keys.toList.map(v=>List(v->(pprop_0(v),wprop_0(v))).toMap),30)){(s,i) =>
      val a = s.map(_.get())
      val wprop_l = s.map(v=>math.exp(math.log(v(1))+eps*(v(0))))
      val grad_l = s.keys.map(v=>grad_U(v,m_t(v),c_t(v),c_t_1(v),tau,phi,w_t,w_t_ast))

      val grad_l = s._2.map(v=>grad_U(v,m_t(v),c_t(v),c_t_1(v),tau,phi,w_t,w_t_ast)))
      val pprop_l = s._1.map(v=>(p(v)+eps*grad_l(v))))

      //val wprop_l = sc.parallelize(s._2.keys.toList,30).map(v=>List(v->math.exp(math.log(s._2(v))+eps*s._1(v))).toMap).reduce(_++_)
      //val grad_l = sc.parallelize(s._2.keys.toList,30).map(v=>List(v->grad_U(v,m_t(v),c_t(v),c_t_1(v),tau,phi,w_t,w_t_ast)).toMap).reduce(_++_)
      //val pprop_l = sc.parallelize(s._1.keys.toList,30).map(v=>List(v->(p(v)+eps*grad_l(v))).toMap).reduce(_++_)
      if (i==L-1){
        val grad_last = s._2.keys.toList.map(v=>(v,grad_U(v,m_t(v),c_t(v),c_t_1(v),tau,phi,wprop_l,w_t_ast))).toMap
        val pprop_last = s._1.keys.toList.map(v=> (v, -(p(v)+eps*grad_last(v)/2))).toMap

        //val grad_last = sc.parallelize(s._2.keys.toList,30).map(v=>List(v->grad_U(v,m_t(v),c_t(v),c_t_1(v),tau,phi,wprop_l,w_t_ast)).toMap).reduce(_++_)
        //val pprop_last = sc.parallelize(s._1.keys.toList,30).map(v=> List(v-> -(p(v)+eps*grad_last(v)/2)).toMap).reduce(_++_)
        (pprop_last,wprop_l)
        }
      else{
        (pprop_l,wprop_l)
        }
      (pprop_l,wprop_l)
      }
    val logr = sc.parallelize(w_t.keys.toList,30).map(v=>(m_t(v)+c_t(v)+c_t_1(v))*(math.log(wprop(v))-math.log(w_t(v)))).reduce(_+_)+
    -math.pow(sc.parallelize(w_t.keys.toList,30).map(v=>wprop(v)+w_t_ast).reduce(_+_),2)+math.pow(sc.parallelize(w_t.keys.toList,30).map(v=>w_t(v)+w_t_ast).reduce(_+_),2)-
      (2*phi+tau)*(sc.parallelize(w_t.keys.toList,30).map(v=>wprop(v)).reduce(_+_)-sc.parallelize(w_t.keys.toList,30).map(v=>w_t(v)).reduce(_+_))-
      sc.parallelize(w_t.keys.toList,30).map(v=>math.pow(pprop(v),2)-math.pow(p(v),2)).reduce(_+_)/2
    print(math.exp(logr))
    //println(wprop.values.mkString("\n"))
    //println(w_t.values.mkString("\n"))*/
    if (math.log(nextDouble)<logr){
      val  wprop_next = ret_prep.map(v=>List(v._1->v._2._2.toList(0)).toMap).reduce(_++_)
      return wprop_next
    }else{
      return w_t
   }
  }

  def efficient_pois(lambda:Double): Int ={
    val r = new Random()
    val ret =
      if(lambda ==0){0}
      else if(lambda<1000){
      val p = new Poisson(lambda)
      p.sample(1)(0)}else{
      //Gaussian(lambda,math.sqrt(lambda)).sample(1)(0).toInt
      val tmp = (r.nextGaussian*math.sqrt(lambda)+lambda).toInt
      tmp
      }
    return if(ret<0){0}else if(ret == Int.MaxValue){Int.MaxValue-1}else{ret}
  }
    def safelog(x:Double):BigDecimal={
      val ret = if(math.log(x) == Double.PositiveInfinity){BigDecimal(Double.MaxValue)}else if (math.log(x)== Double.NegativeInfinity){BigDecimal(Double.MinValue)}else{BigDecimal(math.log(x))}
      return ret
    }
//update_wt(networks(0),w_0T(1),w_0T_ast(1),c_0T(1),c_0T(0),tau,phi,5,0.3)
  def composePairs(nums: List[Int]) =
    nums.flatMap(x => nums.map(y => (x,y))).filter(v=> v._1!=v._2)

  def bessi(n: Int, x: Double) = {
    val ACC = 4.0
    val BIGNO = 1.0e10
    val BIGNI = 1.0e-10
    if (n < 2){
      if(n == 1){
        Bessel.i1(x)
      }else{Bessel.i0(x)}
    }
    else if (x == 0.0 && n!= 0) 0.0
    else {
      val tox = 2.0 / Math.abs(x)
      var ans = 0.0
      var bip = 0.0
      var bi = 1.0
      var j = 2 * (n + Math.sqrt(ACC * n).toInt)
      while (j > 0) {
        val bim = bip + j * tox * bi
        bip = bi
        bi = bim
        if (Math.abs(bi) > BIGNO) {
          ans *= BIGNI
          bi *= BIGNI
          bip *= BIGNI
        }
        if (j == n) ans = bip
        j -= 1
      }
      ans *= Bessel.i0(x) / bi
      if (((x < 0.0) && ((n % 2) == 0))) -(ans)
      else ans
    }
  }
 def safeadd(l:Double,r:Double): Double ={
  // print(l)
   if (l.isInfinite) l
   else if (l<r) r *(1+math.exp(math.log(l)-math.log(r)))
   else l*(1+math.exp(math.log(r)-math.log(l)))
 }
  def grad_U(idx:Int,m_i:Int,c_t:Int,c_t_1:Int,tau:Double,phi:Double,
             w_t:Map[Int,Double],w_t_ast:Double,alpha:Double): Double ={
    val out = (c_t+c_t_1+m_i)-w_t(idx)*(tau+2*phi+2*math.exp(-alpha*math.log(1+1/tau))*w_t.values.sum+2*w_t_ast)
    return -out
  }
  def update_ct(D_t:org.apache.spark.rdd.RDD[((Int, Int), Int)],D_t1:org.apache.spark.rdd.RDD[((Int, Int), Int)],w_t:Map[Int,Double],w_t1:Map[Int,Double],w_t_ast:Double,
                w_t1_ast:Double,c_t:Map[Int,Int], c_t1:Map[Int,Int],c_tm1:Map[Int,Int],tau:Double,phi:Double,L:Int,
                eps:Double,sc:SparkContext): (Map[Int,Int],Map[Int,Double])={
    var w_new = Map[Int,Double]()
    val numpart = 24
    val D_tt =D_t //sc.parallelize(D_t.toList,numpart)
    val tmp_mt = sc.parallelize(w_t.toList,numpart).cogroup(D_tt.map(v=>(v._1._1,v._2)),D_tt.map(v=>(v._1._2,v._2)))
    val m_t = tmp_mt.map(v=>List(v._1->(v._2._2.toList.sum+v._2._3.toList.sum)).toMap).treeReduce(_++_,3)

    //val m_t = sc.parallelize(w_t.keys.toList,1000).map(v=>List(v->(D_t.filter(vv=>vv._1._1==v).values.sum+D_t.filter(vv=>vv._1._2 == v).values.sum)).toMap).reduce(_++_)
    //val m_t = D_t.keys.map(v=>(v._1,D_t.filter(vv=>vv._1._1==v._1).values.sum+D_t.filter(vv=>vv._1._2 == v._1).values.sum)).toMap
    val present = D_t.filter(v=>v._2!=0).keys.map(v=>v._1).collect.toSet.union(D_t.filter(v=>v._2!=0).keys.map(v=>v._2).collect.toSet).toArray
    val nonzero_w = present.map(v=>(v,w_t1(v))).toMap.filter(v=>v._2!=0).keys.toArray
    val cprop = nonzero_w.map(v=>(v,efficient_pois(phi*w_t(v))+1)).toMap
    cprop.filter(v=>v._2<=0).foreach(print)
    val rr =   nonzero_w.map(v=>math.log(Gamma(c_t(v),1/(tau+phi)).pdf(w_t1(v))))
    val r1 = nonzero_w.map(v=>(v,math.log(Gamma(cprop(v),1/(tau+phi)).pdf(w_t1(v)))-
      math.log(Gamma(c_t(v),1/(tau+phi)).pdf(w_t1(v))))).toMap
    var c_new = nonzero_w.foldLeft(c_t)((s,i)=>if(math.log(nextDouble)<r1(i)){s+(i->cprop(i))}else{s})

    val present1 = D_t1.filter(v=>v._2!=0).keys.map(v=>v._1).collect.toSet.union(D_t1.filter(v=>v._2!=0).keys.map(v=>v._2).collect.toSet).toArray
    val zero_w = present1.map(v=>(v,w_t1(v))).toMap.filter(v=>v._2==0).keys.toArray
    c_new = zero_w.foldLeft(c_new)((s,i)=>if(nextDouble<1/(1+phi*w_t(i)*(tau+phi))){s+(i->0)}else{s+(i->1)})
    //println(zero_w.length)
    //joint sampling
    val R = w_t1.values.sum+w_t1_ast+zero_w.map(v=>w_t1(v)).sum
    val tmp = joint_update(zero_w.map(v=>(v,c_t(v))).toMap,zero_w.map(v=>(v,c_t1(v))).toMap,
                           zero_w.map(v=>(v,w_t(v))).toMap,zero_w.map(v=>(v,w_t1(v))).toMap,
                           phi,tau,w_t1_ast,R)

    c_new = tmp._1.foldLeft(c_new)((s,i)=> s+(i._1->i._2))
    w_new = tmp._2.foldLeft(w_new)((s,i)=> s+(i._1->i._2))
    //rev joint sampling
    /*
    val zero_w_rev = present.map(v=>(v,w_t(v))).toMap.filter(v=>v._2==0).keys.toArray
    val R_rev = w_t.values.sum+w_t_ast+zero_w_rev.map(v=>w_t(v)).sum
    val tmp_2 = joint_update(zero_w_rev.map(v=>(v,c_t(v))).toMap,zero_w_rev.map(v=>(v,c_tm1(v))).toMap,
      zero_w_rev.map(v=>(v,w_t1(v))).toMap,zero_w_rev.map(v=>(v,w_t(v))).toMap,
      phi,tau,w_t_ast,R_rev)
    c_new = tmp_2._1.foldLeft(c_new)((s,i)=> s+(i._1->i._2))
    w_new = tmp_2._2.foldLeft(w_new)((s,i)=> s+(i._1->i._2))
    */
    return (c_new,w_new)
  }
  def joint_update(c_t_z:Map[Int,Int],c_t1_z:Map[Int,Int],w_t_z:Map[Int,Double],w_t1_z:Map[Int,Double],phi:Double,
                   tau:Double,w_t1_ast:Double,R:Double): (Map[Int,Int],Map[Int,Double]) ={
    //print(w_t_z.slice(0,20))
    val cprop = w_t_z.map(v=>(v._1,efficient_pois(phi*v._2)))
    val wprop = cprop.map(v=>(v._1,if(v._2 ==0){0.0}else{Gamma(cprop(v._1),1/(phi+tau)).sample(1)(0)}))
    //println(cprop.keys.toList.slice(0,10))
    //println(wprop.keys.toList.slice(0,10))
    val r = wprop.keys.toList.map(v=>(v,(cprop(v)-c_t_z(v)+c_t1_z(v))*math.log(wprop(v))-
      (cprop(v)-c_t_z(v)+c_t1_z(v))*math.log(w_t1_z(v))+(2*cprop(v)-2*c_t_z(v))*math.log(phi+tau)-
       math.pow(w_t1_z(v),2)-math.pow(wprop(v),2)+(2*(R-w_t1_z(v))+phi)*(wprop(v)-w_t1_z(v)))).toMap//+
      //(2*logGamma(c_t_z(v))-2*logGamma(cprop(v))))).toMap
    val (cnew,wnew)= r.foldLeft((c_t_z,w_t1_z)){(s,i)=>
      if(math.log(nextDouble)<i._2){(s._1+(i._1->cprop(i._1)),s._2+(i._1->wprop(i._1)))}
    else{if (i._2.isNaN){(s._1+(i._1->0),s._2+(i._1->0.0))}else{(s._1+(i._1->c_t_z(i._1)),s._2+(i._1->w_t1_z(i._1)))}}}
    //print("\n")
    //print(r.slice(0,20))
    //print("\n")
    //print((r zip wnew).filter(i=>i._1._2.isNaN != true).map(v=>v._2).slice(0,10))
    //print("\n")
    return (cnew,wnew)
  }
  def zpois(lamb:Double): Int ={
  if (lamb<1e-5){return 1}
  else{
    val prob = math.exp(-lamb)+nextDouble*(1-math.exp(-lamb))
    var flag = true
    var value = 1
    while(flag){
      flag = Poisson(lamb).cdf(value)<prob
      value+=1
      }
    return value - 1
    }
  }
  def update_n(Z_t:Map[(Int,Int),Int],n_t_new:org.apache.spark.rdd.RDD[((Int, Int), Int)],n_t_old:org.apache.spark.rdd.RDD[((Int, Int), Int)],
               n_t_1:Map[(Int,Int),Int],rho:Double, w_t:Map[Int,Double],n_t1_old:Map[(Int,Int),Int],sc:SparkContext,alpha:Double,tau:Double): org.apache.spark.rdd.RDD[((Int, Int), Int)] ={
    val g_t = math.exp(-alpha*math.log(1+1/tau))
    val ret = n_t_new.join(n_t_old).map(v=>List(v._1->v._2).toMap).map{ i =>
      val return_val =
      if (Z_t(i.keys.toList(0)) == 0) {
        val tmp = (i.keys.toList(0) -> (0, 0))
        tmp
      }
      else {
        val ret_old = if (n_t_1(i.keys.toList(0)) == 0) {
          0
        } else {
          Binomial(n_t_1(i.keys.toList(0)), math.exp(-rho)).sample(1)(0)
        }
        val ret_new = if (ret_old == 0) {
          efficient_pois(2 * g_t*w_t(i.keys.toList(0)._1) * w_t(i.keys.toList(0)._2))+1
        } else {
          efficient_pois(2 * g_t*w_t(i.keys.toList(0)._1) * w_t(i.keys.toList(0)._2))
        }
        val n_prop = ret_old + ret_new
        val n_prev = i.values.toList(0)._1 + i.values.toList(0)._2
        val hoge = 10 - n_t1_old(i.keys.toList(0))

        val logr = (1 to n_prop).map(math.log(_)).sum - (1 to n_prev).map(math.log(_)).sum + (n_prop - n_prev) * math.log(1 - math.exp(-rho)) +
          (1 to (n_prev - n_t1_old(i.keys.toList(0)))).map(v => math.log(v)).sum - (1 to (n_prop - n_t1_old(i.keys.toList(0)))).map(v => math.log(v)).sum
        //print("\n")
        //printf("n_logr: "+logr.toString+" "+i.values.toList(0)._1.toString+":"+ret_new.toString)
        //print("\n")
        val tmp = if (logr > math.log(nextDouble)) {
          (i.keys.toList(0) -> (ret_new, ret_old))
          } else {
          (i.keys.toList(0) -> (i.values.toList(0)._1, i.values.toList(0)._2))
          }
        tmp
        }
       return_val
      }.map(v=>(v._1,v._2._1))//.map(v=>v.map(vv=>(vv._1,vv._2._1))) //.treeReduce(_++_,5)
     for((k,v)<- sc.getPersistentRDDs){v.unpersist()}
    //val ret_val = ret.map(v=>(v._1,v._2._1))

    return ret
  }
    /*
    val (n_t_new_s,n_t_old_s) = Z_t.foldLeft((Map[(Int,Int),Int](),Map[(Int,Int),Int]())){(s,i)=>
      if (i._2 ==0){(s._1+(i._1->0),s._2+(i._1->0))}
      else{
        val ret_old = if(n_t_1(i._1) ==0){0} else{Binomial(n_t_1(i._1),math.exp(-rho)).sample(1)(0)}
        val ret_new =  if (ret_old == 0){zpois(2*w_t(i._1._1)*w_t(i._1._2))}
        else{Poisson(2*w_t(i._1._1)*w_t(i._1._2)).sample(1)(0)}
        val n_prop = ret_old+ret_new
        val n_prev = n_t_new(i._1)+n_t_old(i._1)
        val logr = (1 to n_prop).map(math.log(_)).sum-(1 to n_prev).map(math.log(_)).sum+
          (1 to(n_prev - n_t1_old(i._1))).map(math.log(_)).sum-(1 to(n_prop- n_t1_old(i._1))).map(math.log(_)).sum+
          (n_prop-n_prev)*math.log(1-math.exp(-rho))
        if(logr>math.log(nextDouble)){
          (s._1+(i._1->ret_new),s._2+(i._1->ret_old))
        }else{
          (s._1+(i._1->n_t_new(i._1)),s._2+(i._1->n_t_old(i._1)))
        }
      }
    }
    return n_t_new_s //,n_t_old_s)*/

  def update_c_t_ast(c_t_ast:Int,w_t_ast:Double,w_t1_ast:Double,phi:Double,tau:Double,alpha:Double): Int ={
    val c_prop = efficient_pois(phi*w_t_ast)
    val logr = (c_prop-c_t_ast)*math.log((phi+tau)*w_t1_ast)+logGamma(alpha+c_t_ast)-logGamma(alpha+c_prop)
    //printf("c_t_ast:"+logr.toString," ")
    if(logr>math.log(nextDouble)){
      return c_prop
    }else{
      return c_t_ast}
  }
  def update_w_t_ast(c_t_ast:Int,c_t_1_ast:Int,w_t_ast:Double,w_t:Map[Int,Double],
                     phi:Double,tau:Double,alpha:Double): Double ={
    val gam_t = math.exp(-alpha*math.log(1+1/tau))
    val w_prop = Gamma(alpha+c_t_1_ast+c_t_ast,1/(tau+2*phi+2*gam_t*w_t.values.sum+gam_t*w_t_ast)).sample(1)(0)
    val logr = gam_t*(math.pow(w_t_ast,2)-math.pow(w_prop,2))+(alpha+c_t_ast+c_t_1_ast)*
      (math.log(tau+2*phi+2*gam_t*w_t.values.sum+gam_t*w_prop)-math.log(tau+2*phi+2*gam_t*w_t.values.sum+gam_t*w_t_ast))
    //printf("\n"+"w_tast:"+logr.toString+":"+math.exp(logr).toString+"\n")
    if(logr>math.log(nextDouble)){
      return w_prop
    }else{w_t_ast}
  }

  def cond_wt1(w_t1:Map[Int,Double],w_t:Map[Int,Double],phi:Double,tau:Double): Array[Double] ={
  val logval = w_t1.keys.map{v=> -phi*w_t(v)*I(w_t1(v)==0.0)+
    math.log(Bessel.i1(2*math.sqrt(w_t1(v)*phi*w_t(v)*(tau+phi))))+math.log(phi*(tau+phi)*w_t(v))/2-
    math.log(w_t1(v))/2-(phi*(w_t1(v)+w_t(v))-tau*w_t1(v))
  }.toArray
    return logval
  }
  def cond_wt_ast(w_t1_ast:Double,w_t_ast:Double,phi:Double,tau:Double,alpha:Double):Double={
    //print(2*math.sqrt(w_t1_ast*phi*w_t_ast*(tau+phi)))
    val tmp = math.log(bessi((alpha-1).toInt,2*math.sqrt(w_t1_ast*phi*w_t_ast*(tau+phi))))
    //print(tmp)
    //val tmp1 =  if(tmp == Double.PositiveInfinity){BigDecimal(Double.MaxValue)}else if (tmp== Double.NegativeInfinity){BigDecimal(Double.MinValue)}else{BigDecimal(tmp)}
    //val tmp2 =if(math.log(w_t1_ast) == Double.PositiveInfinity){BigDecimal(Double.MaxValue)}else if (math.log(w_t1_ast)== Double.NegativeInfinity){BigDecimal(Double.MinValue)}else{BigDecimal(math.log(w_t1_ast))}
    //val tmp3 = if(math.log(w_t_ast*phi) == Double.PositiveInfinity){BigDecimal(Double.MaxValue)}else if (math.log(w_t_ast*phi)== Double.NegativeInfinity){BigDecimal(Double.MinValue)}else{BigDecimal(math.log(w_t_ast*phi))}
    val logval = tmp+
      (alpha+1)/2*math.log(tau+phi)+(alpha-1)/2*(math.log(w_t1_ast)-math.log(w_t_ast*phi))-
    phi*(w_t1_ast+w_t_ast)-tau*w_t1_ast
    //print(math.log(bessi((alpha-1).toInt,2*math.sqrt(w_t1_ast*phi*w_t_ast*(tau+phi)))))
    //print(phi*(w_t1_ast+w_t_ast)-tau*w_t1_ast)
    //val ret = if(logval == Double.PositiveInfinity){BigDecimal(Double.MaxValue)}else if (logval== Double.NegativeInfinity){BigDecimal(Double.MinValue)}else{BigDecimal(logval)}
    return logval
  }
  def sample_exact(c_t:Map[Double,Double],tau:Double,phi:Double): Unit ={
    val w_t_1 = c_t.map(v=>(v._1,Gamma(v._2,tau+phi).sample(1)(0)))
    val c_t_1 = w_t_1.map(v=>(v._1,efficient_pois(phi*v._2).toDouble))

  }
  def CRP(alpha:Double,tau:Double,phi:Double): Map[Double,Double] ={
    val w_t_1_ast = Gamma(alpha,tau+phi).sample(1).toArray
    val c_t_1_ast = Poisson(phi*w_t_1_ast(0)).sample(1).toArray
    val partition = (0 until c_t_1_ast(0)).foldLeft(Map.empty[Double,Double])((s,i)=>assign_seat(i,alpha,s))
    return partition
  }

  def assign_seat(idx:Int,alpha:Double,tables:Map[Double,Double]): Map[Double,Double]={
    if (idx ==0){return tables +(idx.toDouble->1.0)}
    else if (nextDouble<(alpha/(idx+alpha))){return tables+((tables.keys.max+1)->1.0)}
    else{
      val tmp_v = DenseVector(tables.values.toArray)
      val tmp = Multinomial(tmp_v).sample(1).toArray
      return tables+(tmp(0).toDouble -> (tables(tmp(0).toDouble)+1.0))
    }
  }


}
