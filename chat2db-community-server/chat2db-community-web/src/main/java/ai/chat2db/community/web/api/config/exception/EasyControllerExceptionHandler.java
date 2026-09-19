package ai.chat2db.community.web.api.config.exception;

import java.util.Map;

import ai.chat2db.community.web.api.config.exception.convertor.*;
import com.alibaba.fastjson2.JSON;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.NeedLoggedInBusinessException;
import ai.chat2db.community.tools.exception.RedirectBusinessException;
import ai.chat2db.community.tools.exception.SystemException;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import com.google.common.collect.Maps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;


@ControllerAdvice
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EasyControllerExceptionHandler {


    public static final Map<Class<?>, IExceptionConvertor> EXCEPTION_CONVERTOR_MAP = Maps.newHashMap();

    static {
        EXCEPTION_CONVERTOR_MAP.put(MethodArgumentNotValidException.class,
                new MethodArgumentNotValidExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(BindException.class, new BindExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(BusinessException.class, new BusinessExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(NeedLoggedInBusinessException.class, new BusinessExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(MissingServletRequestParameterException.class, new ParamExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(IllegalArgumentException.class, new ParamExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(MethodArgumentTypeMismatchException.class,
                new MethodArgumentTypeMismatchExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(MaxUploadSizeExceededException.class,
                new MaxUploadSizeExceededExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(AgentRuntimeUnavailableException.class,
                new AgentRuntimeUnavailableExceptionConvertor());
    }


    public static IExceptionConvertor DEFAULT_EXCEPTION_CONVERTOR = new DefaultExceptionConvertor();


    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, IllegalArgumentException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            BusinessException.class, MaxUploadSizeExceededException.class, ClientAbortException.class,
            HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotAcceptableException.class,
            MultipartException.class, MissingRequestHeaderException.class, HttpMediaTypeNotSupportedException.class,
            NeedLoggedInBusinessException.class, AgentRuntimeUnavailableException.class})
    @ResponseStatus(value = HttpStatus.OK)
    @ResponseBody
    public ActionResult handleBusinessException(HttpServletRequest request, Exception exception) {
        ActionResult result = convert(exception);
        log.info("Business exception occurred for {}: {}", request.getRequestURI(), result, exception);
        return result;
    }

    @ExceptionHandler({RedirectBusinessException.class})
    public ModelAndView handleModelAndViewBizException(HttpServletRequest request, Exception exception) {
        ModelAndView result = translateModelAndView(exception);
        log.info("ModelAndView business exception occurred for {}: {}", request.getRequestURI(), result, exception);
        return result;
    }

    public ModelAndView translateModelAndView(Throwable exception) {
        if (exception instanceof RedirectBusinessException) {
            RedirectBusinessException e = (RedirectBusinessException) exception;
            return dealResponseModelAndView(null, e.getMessage(), e.getRedirect(), null, null);
        }
        return new ModelAndView("redirect:/");
    }

    private ModelAndView dealResponseModelAndView(String title, String errorMessage, String redirect, String href,
                                                  String buttonText) {
        if (StringUtils.isNotBlank(redirect)) {
            return new ModelAndView("redirect:" + redirect);
        }
        return new ModelAndView("redirect:/");
    }


    @ExceptionHandler({SystemException.class})
    @ResponseStatus(value = HttpStatus.OK)
    @ResponseBody
    public ActionResult handleSystemException(HttpServletRequest request, Exception exception) {
        ActionResult result = convert(exception);
        log.error("System exception occurred for {}: {}", request.getRequestURI(), result, exception);
        return result;
    }


    @ExceptionHandler(Exception.class)
    @ResponseStatus(value = HttpStatus.OK)
    @ResponseBody
    public ActionResult handledException(HttpServletRequest request, Exception exception) {
        ActionResult result = convert(exception);
        log.error("Unexpected exception occurred for {}: {}, request parameters: {}", request.getRequestURI(), result,
                JSON.toJSONString(request.getParameterMap()),
                exception);
        return result;
    }


    @ExceptionHandler(NoHandlerFoundException.class)
    public Object handleNoHandlerFound(NoHandlerFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(HttpStatus.OK.value());
        return "forward:/";
    }

    public ActionResult convert(Throwable exception) {
        IExceptionConvertor exceptionConvertor = EXCEPTION_CONVERTOR_MAP.get(exception.getClass());
        if (exceptionConvertor == null) {
            if (exception instanceof BusinessException) {
                exceptionConvertor = EXCEPTION_CONVERTOR_MAP.get(BusinessException.class);
            } else {
                exceptionConvertor = DEFAULT_EXCEPTION_CONVERTOR;
            }
        }
        ActionResult result = exceptionConvertor.convert(exception);
        result.errorDetail(null);
        return result;
    }
}
