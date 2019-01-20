//package com.nathanielmay.randpix
//
//import com.sun.tools.javac.util.Name.Table
//import com.thoughtworks.binding.Binding
//import com.thoughtworks.binding.Binding.{Var, Vars}
//import scala.scalajs.js
//
////TODO example taken from https://github.com/ThoughtWorksInc/Binding.scala
//final class RandPixUI {
//
//  case class Contact(name: Var[String], email: Var[String])
//
//  val data = Vars.empty[Contact]
//
//  @dom
//  def table: Binding[Table] = {
//    <table border="1" cellPadding="5">
//      <thead>
//        <tr>
//          <th>Name</th>
//          <th>E-mail</th>
//        </tr>
//      </thead>
//      <tbody>
//        {
//        for (contact <- data) yield {
//          <tr>
//            <td>
//              {contact.name.bind}
//            </td>
//            <td>
//              {contact.email.bind}
//            </td>
//          </tr>
//        }
//        }
//      </tbody>
//    </table>
//  }
//
//}
