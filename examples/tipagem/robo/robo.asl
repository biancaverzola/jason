{ include("tipos_extra.asl") }

localizacao(robo, pos(3, 5)).
caminho([pos(1,2), pos(3,4)]).
ligado(true).
nome("r2d2").
longe(X) :- localizacao(robo, pos(X, _)) & X > 2.

!andar.

+!andar
   : localizacao(robo, pos(X, Y)) & caminho([pos(A, B) | Resto]) & ligado(L)
  <- N = X + A;
     -+localizacao(robo, pos(N, Y));
     if (N > 3) { .print("longe ", N) } else { .print("perto") };
     for (.member(P, Resto)) { .print(P) };
     !mover(N).

+!mover(D) <- .print("movendo ", D).
