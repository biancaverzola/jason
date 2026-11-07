// b usa os tipos do msg.mas2j: temperatura(atom, number)

+temperatura(forno, T)[source(A)] : T > 180
  <- .print("recebi T = ", T, " de ", A, "  ->  forno QUENTE").

+temperatura(forno, T)[source(A)]
  <- .print("recebi T = ", T, " de ", A, "  ->  forno frio").

// só é usado com jason.typecheck.runtime.onfail = fail:
// trata a mensagem recusada por causa do tipo errado
-!kqml_received(S, tell, C, _)[error(type_error), error_msg(M)]
  <- .print("recusei ", C, " de ", S, ": ", M).
