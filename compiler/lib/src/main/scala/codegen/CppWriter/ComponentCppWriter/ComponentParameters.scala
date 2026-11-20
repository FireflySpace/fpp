package fpp.compiler.codegen

import fpp.compiler.analysis._
import fpp.compiler.ast._
import fpp.compiler.codegen._

/** Writes out C++ for component parameters */
case class ComponentParameters (
  s: CppWriterState,
  aNode: Ast.Annotated[AstNode[Ast.DefComponent]]
) extends ComponentCppWriterUtils(s, aNode) {

  val paramBufferName = "_paramBuffer"

  def getConstantMembers: List[CppDoc.Class.Member] =
    guardedList (hasParameters) (List(getParamIds))

  def getPublicFunctionMembers: List[CppDoc.Class.Member] =
    guardedList (hasParameters) (getLoadFunction)

  def getProtectedFunctionMembers: List[CppDoc.Class.Member] =
    guardedList (hasParameters) (
      List.concat(
        getHookFunctions,
        getGetterFunctions,
        getExternalParameterFunctions
      )
    )

  def getPrivateFunctionMembers: List[CppDoc.Class.Member] =
    guardedList (hasParameters) (
      List.concat(
        getSetters,
        getSaveFunctions
      )
    )

  def getVariableMembers: List[CppDoc.Class.Member] =
    List.concat(
      getValidityFlags,
      getParamVars,
      guardedList (hasExternalParameters) (getParamDelegate)
    )

  private def checkValidityFlag(flag: String, flagValue: String): String =
    s"$flag == Fw::ParamValid::$flagValue"

  private def checkValidityFlag(param: Param, flagValue: String): String =
    checkValidityFlag(s"this->${paramValidityFlagName(param.getName)}", flagValue)

  private def checkValidityFlagValidOrDefault(param: Param) =
    s"(${checkValidityFlag(param, "VALID")}) || (${checkValidityFlag(param, "DEFAULT")})"

  private def deserializeParam(param: Param) = {
    val paramName = param.getName
    val varName = paramVariableName(paramName)
    val validityFlagName = paramValidityFlagName(paramName)
    if param.isExternal
    then
      val idConstantName = paramIdConstantName(paramName)
      lines(
        s"""|FW_ASSERT(this->paramDelegatePtr != nullptr);
            |_stat = this->paramDelegatePtr->deserializeParam(
            |  _baseId,
            |  $idConstantName,
            |  this->$validityFlagName,
            |  $paramBufferName
            |);"""
      )
    else
      lines(s"_stat = $paramBufferName.deserializeTo(this->$varName);")
  }

  private def getExternalParameterFunctions: List[CppDoc.Class.Member] = {
    lazy val delegateInit = addAccessTagAndComment(
      "protected",
      "External parameter delegate initialization",
      List(
        functionClassMember(
          Some("Initialize the external parameter delegate"),
          "registerExternalParameters",
          List(
            CppDoc.Function.Param(
              CppDoc.Type("Fw::ParamExternalDelegate*"),
              "paramExternalDelegatePtr",
              Some("The delegate for externally managed parameters")
            )
          ),
          CppDoc.Type("void"),
          lines(
            """|FW_ASSERT(paramExternalDelegatePtr != nullptr);
               |this->paramDelegatePtr = paramExternalDelegatePtr;
               |"""
          )
        )
      )
    )
    guardedList (hasExternalParameters) (delegateInit)
  }

  private def getGetterFunctionForParam(param: Param) =
    functionClassMember(
      Some(
        addSeparatedString(
          s"Get parameter ${param.getName}\n\n\\return The parameter value",
          AnnotationCppWriter.asStringOpt(param.aNode)
        )
      ),
      paramGetterName(param.getName),
      List(
        CppDoc.Function.Param(
          CppDoc.Type("Fw::ParamValid&"),
          "valid",
          Some("Whether the parameter is valid")
        )
      ),
      CppDoc.Type(writeParamType(param.paramType, "Fw::ParamString")),
      writeGetterFunctionBody(param)
    )

  private def getGetterFunctionNoValidForParam(param: Param) =
    functionClassMember(
      Some(
        addSeparatedString(
          s"Get parameter ${param.getName}\n\n\\return The parameter value",
          AnnotationCppWriter.asStringOpt(param.aNode)
        )
      ),
      paramGetterName(param.getName),
      Nil,
      CppDoc.Type(writeParamType(param.paramType, "Fw::ParamString")),
      lines(
        s"""|Fw::ParamValid _valid{};
            |return this->${paramGetterName(param.getName)}(_valid);"""
      )
    )

  private def getGetterFunctions: List[CppDoc.Class.Member] =
    addAccessTagAndComment(
      "protected",
      "Parameter get functions",
      sortedParams.flatMap((_, param) => List(
        getGetterFunctionForParam(param),
        getGetterFunctionNoValidForParam(param)
      ))
    )

  private def getHookFunctions: List[CppDoc.Class.Member] = {
    addAccessTagAndComment(
      "protected",
      "Parameter hook functions",
      List(
        getParamUpdateHookFunction,
        getParamTelemeterHookFunction,
        getParamLoadHookFunction,
        getParamsLoadHookFunction
      )
    )
  }

  private def getLoadFunction: List[CppDoc.Class.Member] =
    addAccessTagAndComment(
      "public",
      "Parameter loading",
      List(
        functionClassMember(
          Some(
            s"""|\\brief Load the parameters from a parameter source
                |
                |Connect the parameter first
                |"""
          ),
          "loadParameters",
          Nil,
          CppDoc.Type("void"),
          writeLoadFunctionBody
        )
      )
    )

  private def getParamDelegate =
    addAccessTagAndComment(
      "private",
      "Parameter delegate",
      List(
        linesClassMember(
          lines(
            s"""|
                |//! Delegate to serialize/deserialize an externally stored parameter
                |Fw::ParamExternalDelegate* paramDelegatePtr = nullptr;
                |"""
          )
        )
      ),
      CppDoc.Lines.Hpp
    )

  private def getParamFromComponent(param: Param) = {
    if param.isExternal
    then
      val idConstantName = paramIdConstantName(param.getName)
      lines(
        s"""|Fw::ParamBuffer $paramBufferName;
            |FW_ASSERT(this->paramDelegatePtr != nullptr);
            |Fw::SerializeStatus _stat = this->paramDelegatePtr->serializeParam(
            |  static_cast<FwPrmIdType>(this->getIdBase()),
            |  $idConstantName,
            |  $paramBufferName
            |);
            |if(_stat == Fw::FW_SERIALIZE_OK) {
            |  _stat = $paramBufferName.deserializeTo(_local);
            |  FW_ASSERT(_stat == Fw::FW_SERIALIZE_OK, static_cast<FwAssertArgType>(_stat));
            |} else {
            |  valid = Fw::ParamValid::INVALID;
            |}"""
      )
    else
      val variableName = paramVariableName(param.getName)
      lines(s"_local = this->$variableName;")
  }

  private def getParamIds = linesClassMember(
    List.concat(
      Line.blank :: lines(s"//! Parameter IDs"),
      wrapInEnum(sortedParams.flatMap(writeParamIdConstant))
    )
  )

  private def getParamLoadHookFunction = functionClassMember(
    Some(
      s"""|\\brief Called whenever parameters are loaded
          |
          |By default this notifies the component of each parameter via
          |parameterUpdated. You may override it, for example to suppress
          |notification on load.
          |"""
    ),
    "parametersLoaded",
    Nil,
    CppDoc.Type("void"),
    lines("// Do nothing by default"),
    CppDoc.Function.Virtual
  )

  private def getParamsLoadHookFunction = functionClassMember(
    Some(
      s"""|\\brief Called for each parameter when parameters are loaded
          |
          |By default this asserts that the parameter is usable (VALID or
          |DEFAULT) and then notifies the component via parameterUpdated.
          |Override it to relax that contract, e.g. to tolerate UNINIT or
          |INVALID parameters.
          |"""
    ),
    "parameterLoaded",
    List(
      CppDoc.Function.Param(
        CppDoc.Type("FwPrmIdType"),
        "id",
        Some("The parameter ID")
      ),
      CppDoc.Function.Param(
        CppDoc.Type("Fw::ParamValid"),
        "valid",
        Some("The parameter validity status")
      )
    ),
    CppDoc.Type("void"),
    List.concat(
      lines(
        """|FW_ASSERT(
           |  (valid == Fw::ParamValid::VALID) || (valid == Fw::ParamValid::DEFAULT),
           |  static_cast<FwAssertArgType>(id),
           |  static_cast<FwAssertArgType>(valid)
           |);"""
      ),
      writeParamNotify("id")
    ),
    CppDoc.Function.Virtual
  )

  private def getParamUpdateHookFunction = functionClassMember(
    Some(
      s"""|\\brief Called whenever a parameter is updated
          |
          |This function does nothing by default. You may override it.
          |"""
    ),
    "parameterUpdated",
    List(
      CppDoc.Function.Param(
        CppDoc.Type("FwPrmIdType"),
        "id",
        Some("The parameter ID")
      )
    ),
    CppDoc.Type("void"),
    lines("// Do nothing by default"),
    CppDoc.Function.Virtual
  )

  private def getParamTelemeterHookFunction = functionClassMember(
    Some(
      s"""|\\brief Called whenever a parameter is updated, to mirror it into telemetry
          |
          |Every parameter has an identically named telemetry channel. By
          |default this writes that channel with the parameter's current value,
          |skipping it if the parameter is not usable (not VALID or DEFAULT).
          |You may override it, for example to suppress or reshape the mirror.
          |"""
    ),
    "parameterTelemeter",
    List(
      CppDoc.Function.Param(
        CppDoc.Type("FwPrmIdType"),
        "id",
        Some("The parameter ID")
      )
    ),
    CppDoc.Type("void"),
    wrapInSwitch(
      "id",
      sortedParams.flatMap((_, param) => writeParamTelemeterCase(param))
    ),
    CppDoc.Function.Virtual
  )

  private def writeParamTelemeterCase(param: Param): List[Line] =
    lines(
      s"""|case ${paramIdConstantName(param.getName)}:
          |  this->tlmWrite_${param.getName}(this->${paramGetterName(param.getName)}());
          |  break;"""
    )

  private def getParamVarForParam(param: Param) =
    val paramType = writeParamType(param.paramType, "Fw::ParamString")
    val paramVarName = paramVariableName(param.getName)
    linesClassMember(
      List.concat(
        addSeparatedPreComment(
          s"Parameter ${param.getName}",
          AnnotationCppWriter.asStringOpt(param.aNode)
        ),
        lines(s"$paramType $paramVarName;")
      )
    )

  private def getParamVars = addAccessTagAndComment(
    "private",
    "Parameter variables",
    // Only internal paramenters need storage for parameter values
    sortedParams.collect {
      case (_, param) if !param.isExternal => getParamVarForParam(param)
    },
    CppDoc.Lines.Hpp
  )

  private def getSaveFunctionForParam(param: Param) =
    functionClassMember(
      Some(
        s"""|Save parameter ${param.getName}
            |
            |\\return The command response
            |"""
      ),
      paramHandlerName(param.getName, Command.Param.Save),
      Nil,
      CppDoc.Type("Fw::CmdResponse"),
      writeSaveFunctionBodyForParam(param)
    )

  private def getSaveFunctions: List[CppDoc.Class.Member] =
    addAccessTagAndComment(
      "private",
      "Parameter save functions",
      sortedParams.map((_, param) => getSaveFunctionForParam(param))
    )

  private def getSetterForParam(param: Param) =
    functionClassMember(
      Some(
        s"""|Set parameter ${param.getName}
            |
            |\\return The command response
            |"""
      ),
      paramHandlerName(param.getName, Command.Param.Set),
      List(
        CppDoc.Function.Param(
          CppDoc.Type("Fw::SerialBufferBase&"),
          "val",
          Some("The serialization buffer")
        ),
      ),
      CppDoc.Type("Fw::CmdResponse"),
      if (param.isExternal)
      then writeSetterBodyForExternalParam(param)
      else writeSetterBodyForInternalParam(param)
    )

  private def getSetters: List[CppDoc.Class.Member] =
    addAccessTagAndComment(
      "private",
      "Parameter set functions",
      sortedParams.map((_, param) => getSetterForParam(param))
    )

  private def getValidityFlags = addAccessTagAndComment(
    "private",
    "Parameter validity flags",
    sortedParams.map((_, param) => getValidityFlagForParam(param)),
    CppDoc.Lines.Hpp
  )

  private def paramGetterName(name: String) =
    s"paramGet_$name"

  private def paramVariableName(name: String) =
    s"m_$name"

  private def setDefaultValue(param: Param, value: Value) = {
    val paramName = param.getName
    val varName = paramVariableName(paramName)
    val cppValue = ValueCppWriter.write(s, value)
    if param.isExternal
    then
      val cppType = TypeCppWriter.getName(s, value.getType, "Fw::String")
      List.concat(
        setValidityFlagLines(param, "DEFAULT"),
        lines(
          s"""|$cppType _val = $cppValue;
              |$paramBufferName.resetSer();
              |_stat = $paramBufferName.serializeFrom(_val);
              |FW_ASSERT(_stat == Fw::FW_SERIALIZE_OK, static_cast<FwAssertArgType>(_stat));"""
        ),
        // Push the default into the external store; a failed push is INVALID.
        deserializeParam(param),
        wrapInIf("_stat != Fw::FW_SERIALIZE_OK", setValidityFlagLines(param, "INVALID"))
      )
    else
      lines(
        s"""|this->$varName = $cppValue;
            |${setValidityFlag(param, "DEFAULT")}"""
      )
  }

  private def setValidityFlag(param: Param, flagValue: String) =
    val paramName = param.getName
    val validityFlagName = paramValidityFlagName(paramName)
    s"this->$validityFlagName = Fw::ParamValid::$flagValue;"

  private def setValidityFlagLines(param: Param, flagValue: String) =
    lines(setValidityFlag(param, flagValue))

  // Run `body` under the parameter mutex; the ScopeLock releases at block end.
  private def withParamLock(body: List[Line]): List[Line] =
    wrapInBlock(lines("Os::ScopeLock _lock(this->m_paramLock);") ++ body)

  private def writeGetterFunctionBody(param: Param) = {
    val paramType = writeParamType(param.paramType, "Fw::ParamString")
    val validityFlagName = paramValidityFlagName(param.getName)
    List.concat(
      lines(s"$paramType _local{};"),
      withParamLock(
        List.concat(
          lines(s"valid = this->$validityFlagName;"),
          wrapInIf(
            "(valid == Fw::ParamValid::VALID) || (valid == Fw::ParamValid::DEFAULT)",
            getParamFromComponent(param)
          )
        )
      ),
      lines("return _local;")
    )
  }

  private def writeLoadForParam(param: Param) = {
    val idConstant = paramIdConstantName(param.getName)
    val validityFlag = paramValidityFlagName(param.getName)
    List.concat(
      lines(
        s"""|
            |_id = _baseId + $idConstant;
            |_dbValid = this->${outputPortInvokerName(prmGetPort.get)}(0, _id, $paramBufferName);"""
      ),
      // Hold the lock only across the flag/store mutation; notify unlocked.
      withParamLock(writeLoadBodyForParam(param)),
      lines(s"this->parameterLoaded($idConstant, this->$validityFlag);")
    )
  }

  private def writeLoadBodyForParam(param: Param) = {
    val dbOverride = List.concat(
      setValidityFlagLines(param, "VALID"),
      deserializeParam(param),
      wrapInIf(
        "_stat != Fw::FW_SERIALIZE_OK",
        setValidityFlagLines(param, "INVALID")
      )
    )
    param.default match {
      case Some(value) =>
        List.concat(
          wrapInIf(checkValidityFlag("_dbValid", "VALID"), dbOverride),
          wrapInIf(checkValidityFlag(param, "UNINIT"), setDefaultValue(param, value))
        )
      case None =>
        // No default to fall back to: load from the DB iff present, else uninit.
        wrapInIf(checkValidityFlag("_dbValid", "VALID"), dbOverride)
    }
  }

  private def writeLoadFunctionBody = {
    val prmGetPortName = prmGetPort.get.getUnqualifiedName
    val prmGetIsConnected = outputPortIsConnectedName(prmGetPortName)
    // Holds the pre-lock prm DB read result
    val dbValidDecl =
      if sortedParams.nonEmpty
      then lines("Fw::ParamValid _dbValid{};")
      else Nil
    List.concat(
      lines(
        s"""|Fw::SerializeStatus _stat{};
            |const FwPrmIdType _baseId = static_cast<FwPrmIdType>(this->getIdBase());
            |FW_ASSERT(this->$prmGetIsConnected(0));
            |
            |FwPrmIdType _id{};
            |Fw::ParamBuffer $paramBufferName;
            |"""
      ),
      dbValidDecl,
      sortedParams.flatMap((_, param) => writeLoadForParam(param)),
      lines(
        """|
           |// Call notifier
           |this->parametersLoaded();
           |"""
      )
    )
  }

  private def writeParamIdConstant(
    id: Param.Id,
    param: Param
  ) = writeEnumConstant(
    paramIdConstantName(param.getName),
    id,
    AnnotationCppWriter.asStringOpt(param.aNode),
    CppWriterUtils.Hex
  )

  private def writeSaveFunctionBodyForParam(param: Param) = {
    val idConstantName = paramIdConstantName(param.getName)
    val paramVarName = paramVariableName(param.getName)
    val prmSetPortName = prmSetPort.get.getUnqualifiedName
    val prmSetIsConnected = outputPortIsConnectedName(prmSetPortName)
    val prmSetPortInvokerName = outputPortInvokerName(prmSetPort.get)
    List.concat(
      lines(
        s"""|if (!this->$prmSetIsConnected(0)) {
            |  return Fw::CmdResponse::EXECUTION_ERROR;
            |}
            |Fw::ParamBuffer $paramBufferName;
            |const FwIdType idBase = this->getIdBase();
            |Fw::SerializeStatus _stat = Fw::FW_SERIALIZE_FORMAT_ERROR;
            |// Serialize the parameter"""
      ),
      withParamLock(
        wrapInIf(
          checkValidityFlagValidOrDefault(param),
          if (param.isExternal)
          then lines(
            s"""|FW_ASSERT(this->paramDelegatePtr != nullptr);
                |_stat = this->paramDelegatePtr->serializeParam(
                |  static_cast<FwPrmIdType>(idBase),
                |  $idConstantName,
                |  $paramBufferName
                |);"""
          )
          else lines (
            s"_stat = $paramBufferName.serializeFrom($paramVarName);"
          )
        )
      ),
      lines(
        s"""|if (_stat != Fw::FW_SERIALIZE_OK) {
            |  return Fw::CmdResponse::VALIDATION_ERROR;
            |}
            |// Save the parameter
            |this->$prmSetPortInvokerName(
            |  0,
            |  static_cast<FwPrmIdType>(idBase + $idConstantName),
            |  $paramBufferName
            |);
            |// Return the command response
            |return Fw::CmdResponse::OK;"""
      )
    )
  }

  private def writeSetterBodyForExternalParam(param: Param) = {
    val idConstantName = paramIdConstantName(param.getName)
    List.concat(
      lines("Fw::CmdResponse _response{};"),
      withParamLock(
        lines(
          s"""|// Update the external parameter
              |FW_ASSERT(this->paramDelegatePtr != nullptr);
              |const Fw::SerializeStatus _stat = this->paramDelegatePtr->deserializeParam(
              |  static_cast<FwPrmIdType>(this->getIdBase()),
              |  $idConstantName,
              |  Fw::ParamValid::VALID,
              |  val
              |);
              |// Set response and update component state
              |if (_stat == Fw::FW_SERIALIZE_OK) {
              |  ${setValidityFlag(param, "VALID")}
              |  _response = Fw::CmdResponse::OK;
              |}
              |else {
              |  ${setValidityFlag(param, "INVALID")}
              |  _response = Fw::CmdResponse::VALIDATION_ERROR;
              |}"""
        )
      ),
      lines(
        """|
           |// Call notifier"""
      ),
      wrapInIf(
        "_response == Fw::CmdResponse::OK",
        writeParamNotify(idConstantName)
      ),
      lines("return _response;")
    )
  }

  private def writeSetterBodyForInternalParam(param: Param) = {
    val paramType = writeParamType(param.paramType, "Fw::ParamString")
    val varName = paramVariableName(param.getName)
    val validityFlagName = paramValidityFlagName(param.getName)
    val idConstantName = paramIdConstantName(param.getName)
    List.concat(
      lines(
        s"""|$paramType _localVal{};
            |const Fw::SerializeStatus _stat = val.deserializeTo(_localVal);
            |if (_stat != Fw::FW_SERIALIZE_OK) {
            |  return Fw::CmdResponse::VALIDATION_ERROR;
            |}
            |
            |// Assign value only if successfully deserialized"""
      ),
      withParamLock(
        lines(
          s"""|this->$varName = _localVal;
              |this->$validityFlagName = Fw::ParamValid::VALID;"""
        )
      ),
      lines(
        """|
           |// Call notifier"""
      ),
      writeParamNotify(idConstantName),
      lines("return Fw::CmdResponse::OK;")
    )
  }

  /** Emit the post-update notification for a parameter */
  private def writeParamNotify(idExpr: String): List[Line] =
    lines(
      s"""|this->parameterTelemeter($idExpr);
          |this->parameterUpdated($idExpr);"""
    )

}
